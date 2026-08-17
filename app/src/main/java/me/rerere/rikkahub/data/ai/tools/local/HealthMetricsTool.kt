package me.rerere.rikkahub.data.ai.tools.local

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionException
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.HealthMetricDraft
import me.rerere.rikkahub.data.model.HealthMetricRecord
import me.rerere.rikkahub.data.model.HealthMetricType

internal const val HEALTH_METRICS_TOOL_NAME = "health_metrics"

internal fun buildHealthMetricsTool(
    listRecent: suspend (Set<HealthMetricType>?, Int) -> List<HealthMetricRecord>,
    save: suspend (List<HealthMetricDraft>, String) -> List<HealthMetricRecord>,
    delete: suspend (List<String>, String) -> Int,
    canRead: () -> Boolean,
    nowEpochMillis: () -> Long = System::currentTimeMillis,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Tool = Tool(
    name = HEALTH_METRICS_TOOL_NAME,
    description = """
        Store and query the user's structured health measurements. Never infer, estimate, or fabricate a value from
        appearance, a photo, demographics, or vague context. `save` is allowed only when `source_quote` is an exact
        substring of a USER text message that explicitly states every submitted value. Omit `observed_at` unless the
        user explicitly supplied a measurement date/time or clearly said it is current/today. Values are decimal
        strings in each metric's canonical unit. `query` reads recent records only when the user enabled AI health-data
        access. `delete` requires an exact USER quote explicitly requesting deletion and stable record IDs.
    """.trimIndent().replace("\n", " "),
    parameters = { healthMetricsInputSchema() },
    sanitizeInputForStorage = ::sanitizeHealthMetricsInputForStorage,
    needsApproval = { input ->
        val action = (input as? JsonObject)?.stringOrNull("action")?.lowercase(Locale.ROOT)
        action != "query"
    },
    execute = { input ->
        try {
            val params = input as? JsonObject
                ?: throw HealthMetricInputException("INVALID_INPUT", "tool input must be a JSON object")
            val action = params.requiredString("action").lowercase(Locale.ROOT)
            val payload = when (action) {
                "save" -> executeHealthSave(params, save, nowEpochMillis, zoneId)
                "query" -> executeHealthQuery(params, listRecent, canRead)
                "delete" -> executeHealthDelete(params, delete)
                else -> throw HealthMetricInputException(
                    "UNKNOWN_ACTION",
                    "action must be one of [save, query, delete]",
                )
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (error: CancellationException) {
            throw error
        } catch (error: HealthMetricInputException) {
            healthMetricError(error.code, error.message.orEmpty())
        } catch (error: ToolExecutionException) {
            healthMetricError(error.code, error.code)
        } catch (error: IllegalArgumentException) {
            healthMetricError("INVALID_INPUT", error.message ?: "invalid health metric input")
        } catch (_: Exception) {
            healthMetricError("EXECUTION_FAILED", "health metric operation failed")
        }
    },
)

internal fun sanitizeHealthMetricsInputForStorage(input: String): String {
    val params = runCatching { Json.parseToJsonElement(input.ifBlank { "{}" }) as? JsonObject }
        .getOrNull() ?: return "{}"
    val action = params.stringOrNull("action")?.trim()?.lowercase(Locale.ROOT)
    val allowedFields = when (action) {
        "save" -> SAVE_FIELDS
        "query" -> QUERY_FIELDS
        "delete" -> DELETE_FIELDS
        else -> setOf("action")
    }
    return buildJsonObject {
        params.forEach paramsLoop@{ (key, value) ->
            if (key !in allowedFields) return@paramsLoop
            if (key == "measurements") {
                val measurements = value as? JsonArray ?: return@paramsLoop
                put(
                    key,
                    buildJsonArray {
                        measurements.forEach measurementsLoop@{ item ->
                            val measurement = item as? JsonObject ?: return@measurementsLoop
                            add(
                                buildJsonObject {
                                    measurement.forEach { (field, fieldValue) ->
                                        if (field in MEASUREMENT_FIELDS) put(field, fieldValue)
                                    }
                                },
                            )
                        }
                    },
                )
            } else {
                put(key, value)
            }
        }
    }.toString()
}

private suspend fun executeHealthSave(
    params: JsonObject,
    save: suspend (List<HealthMetricDraft>, String) -> List<HealthMetricRecord>,
    nowEpochMillis: () -> Long,
    zoneId: ZoneId,
): JsonObject {
    params.requireOnlyKeys(SAVE_FIELDS, "save")
    val sourceQuote = params.requiredString("source_quote")
    requireHealth(sourceQuote.length in 2..500, "source_quote must contain 2 to 500 characters")
    val array = params["measurements"] as? JsonArray
        ?: throw HealthMetricInputException("INVALID_INPUT", "measurements must be an array")
    requireHealth(array.size in 1..MAX_MEASUREMENTS_PER_SAVE, "measurements must contain 1 to 20 items")
    val now = nowEpochMillis()
    val drafts = array.mapIndexed { index, element ->
        val measurement = element as? JsonObject
            ?: throw HealthMetricInputException("INVALID_INPUT", "measurements[$index] must be an object")
        measurement.requireOnlyKeys(MEASUREMENT_FIELDS, "measurements[$index]")
        val type = measurement.requiredEnum<HealthMetricType>("type")
        val rawValue = measurement.requiredString("value")
        val normalized = runCatching { type.normalizeValue(rawValue) }
            .getOrElse {
                throw HealthMetricInputException(
                    "INVALID_INPUT",
                    it.message ?: "measurements[$index].value is invalid",
                )
            }
        requireHealth(
            sourceQuote.containsExplicitNumber(normalized),
            "measurements[$index].value must appear explicitly in source_quote",
        )
        val observedAt = measurement.stringOrNull("observed_at")?.let { value ->
            parseObservedAt(value, zoneId).also { parsed ->
                requireHealth(parsed <= now + MAX_FUTURE_SKEW_MILLIS, "observed_at must not be in the future")
            }
        }
        HealthMetricDraft(type = type, valueDecimal = normalized, observedAtEpochMillis = observedAt)
    }.distinctBy { Triple(it.type, it.valueDecimal, it.observedAtEpochMillis) }
    val records = save(drafts, sourceQuote)
    return buildJsonObject {
        put("success", true)
        put("action", "save")
        put("count", records.size)
        put("records", buildJsonArray { records.forEach { add(it.toToolJson()) } })
    }
}

private suspend fun executeHealthQuery(
    params: JsonObject,
    listRecent: suspend (Set<HealthMetricType>?, Int) -> List<HealthMetricRecord>,
    canRead: () -> Boolean,
): JsonObject {
    params.requireOnlyKeys(QUERY_FIELDS, "query")
    if (!canRead()) {
        throw HealthMetricInputException(
            "HEALTH_DATA_DISABLED",
            "AI health-data access is disabled in Settings",
        )
    }
    val types = params.optionalTypeSet("types")
    val limit = params.optionalInt("limit") ?: DEFAULT_QUERY_LIMIT
    requireHealth(limit in 1..MAX_QUERY_LIMIT, "limit must be between 1 and 100")
    val records = listRecent(types, limit)
    return buildJsonObject {
        put("success", true)
        put("action", "query")
        put("count", records.size)
        put("records", buildJsonArray { records.forEach { add(it.toToolJson()) } })
    }
}

private suspend fun executeHealthDelete(
    params: JsonObject,
    delete: suspend (List<String>, String) -> Int,
): JsonObject {
    params.requireOnlyKeys(DELETE_FIELDS, "delete")
    val sourceQuote = params.requiredString("source_quote")
    requireHealth(sourceQuote.length in 2..500, "source_quote must contain 2 to 500 characters")
    val ids = params.requiredStringList("record_ids")
    requireHealth(ids.size in 1..MAX_DELETE_COUNT, "record_ids must contain 1 to 100 items")
    val deleted = delete(ids.distinct(), sourceQuote)
    return buildJsonObject {
        put("success", true)
        put("action", "delete")
        put("deleted_count", deleted)
    }
}

private fun healthMetricsInputSchema() = InputSchema.Obj(
    properties = buildJsonObject {
        put("action", enumSchema(listOf("save", "query", "delete"), "Operation to perform."))
        put(
            "source_quote",
            stringSchema(
                "Required for save/delete. Exact substring from a USER text message that explicitly states the " +
                    "submitted values or deletion request.",
            ),
        )
        put(
            "measurements",
            buildJsonObject {
                put("type", "array")
                put("minItems", 1)
                put("maxItems", MAX_MEASUREMENTS_PER_SAVE)
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put(
                            "type",
                            enumSchema(HealthMetricType.entries.map { it.name }, "Health metric in its canonical unit."),
                        )
                        put("value", decimalStringSchema("Explicitly stated value in the metric's canonical unit."))
                        put(
                            "observed_at",
                            stringSchema("Optional explicit measurement date YYYY-MM-DD or ISO 8601 timestamp."),
                        )
                    })
                    put("required", buildJsonArray {
                        add("type")
                        add("value")
                    })
                    put("additionalProperties", false)
                })
            },
        )
        put(
            "types",
            buildJsonObject {
                put("type", "array")
                put("items", enumSchema(HealthMetricType.entries.map { it.name }, "Metric type filter."))
            },
        )
        put("limit", integerSchema("Optional query limit from 1 to 100."))
        put(
            "record_ids",
            buildJsonObject {
                put("type", "array")
                put("items", stringSchema("Stable record ID returned by query."))
            },
        )
    },
    required = listOf("action"),
    additionalProperties = false,
)

private fun HealthMetricRecord.toToolJson() = buildJsonObject {
    put("id", id)
    put("type", type.name)
    put("value", valueDecimal)
    put("unit", unit)
    observedAtEpochMillis?.let { put("observed_at_epoch_ms", it) }
    put("recorded_at_epoch_ms", recordedAtEpochMillis)
    put("source", sourceType.name)
}

private fun parseObservedAt(value: String, zoneId: ZoneId): Long {
    val trimmed = value.trim()
    return runCatching { LocalDate.parse(trimmed).atStartOfDay(zoneId).toInstant().toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
        .recoverCatching { Instant.parse(trimmed).toEpochMilli() }
        .getOrElse {
            throw HealthMetricInputException(
                "INVALID_INPUT",
                "observed_at must use YYYY-MM-DD or ISO 8601 with an offset",
            )
        }
}

private fun String.containsExplicitNumber(expected: String): Boolean {
    val expectedDecimal = expected.toBigDecimalOrNull() ?: return false
    return EXPLICIT_NUMBER.findAll(this).any { match ->
        runCatching { BigDecimal(match.value).compareTo(expectedDecimal) == 0 }.getOrDefault(false)
    }
}

private fun JsonObject.requireOnlyKeys(allowed: Set<String>, context: String) {
    requireHealth(keys.all { it in allowed }, "$context input contains unsupported fields")
}

private fun JsonObject.requiredString(name: String): String {
    val primitive = this[name] as? JsonPrimitive
        ?: throw HealthMetricInputException("INVALID_INPUT", "$name is required and must be a string")
    requireHealth(primitive.isString, "$name must be a string")
    return primitive.content.trim().also { requireHealth(it.isNotBlank(), "$name must not be blank") }
}

private fun JsonObject.stringOrNull(name: String): String? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw HealthMetricInputException("INVALID_INPUT", "$name must be a string")
    requireHealth(primitive.isString, "$name must be a string")
    return primitive.content.trim().takeIf(String::isNotBlank)
}

private fun JsonObject.optionalInt(name: String): Int? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw HealthMetricInputException("INVALID_INPUT", "$name must be an integer")
    requireHealth(!primitive.isString, "$name must be an integer")
    return primitive.intOrNull
        ?: throw HealthMetricInputException("INVALID_INPUT", "$name must be an integer")
}

private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(name: String): T {
    val value = requiredString(name)
    return enumValues<T>().firstOrNull { it.name == value }
        ?: throw HealthMetricInputException(
            "INVALID_INPUT",
            "$name must be one of [${enumValues<T>().joinToString { it.name }}]",
        )
}

private fun JsonObject.optionalTypeSet(name: String): Set<HealthMetricType>? {
    val value = this[name] ?: return null
    val array = value as? JsonArray
        ?: throw HealthMetricInputException("INVALID_INPUT", "$name must be an array")
    requireHealth(array.isNotEmpty(), "$name must not be empty")
    return array.mapIndexed { index, element ->
        val primitive = element as? JsonPrimitive
            ?: throw HealthMetricInputException("INVALID_INPUT", "$name[$index] must be a string")
        requireHealth(primitive.isString, "$name[$index] must be a string")
        HealthMetricType.entries.firstOrNull { it.name == primitive.content }
            ?: throw HealthMetricInputException("INVALID_INPUT", "$name[$index] is unsupported")
    }.toSet()
}

private fun JsonObject.requiredStringList(name: String): List<String> {
    val array = this[name] as? JsonArray
        ?: throw HealthMetricInputException("INVALID_INPUT", "$name is required and must be an array")
    return array.mapIndexed { index, element ->
        val primitive = element as? JsonPrimitive
            ?: throw HealthMetricInputException("INVALID_INPUT", "$name[$index] must be a string")
        requireHealth(primitive.isString, "$name[$index] must be a string")
        primitive.content.trim().also { requireHealth(it.isNotBlank(), "$name[$index] must not be blank") }
    }
}

private fun healthMetricError(code: String, message: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("success", false)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }.toString(),
    ),
)

private fun requireHealth(condition: Boolean, message: String) {
    if (!condition) throw HealthMetricInputException("INVALID_INPUT", message)
}

private fun stringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun integerSchema(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun decimalStringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("pattern", """^(?:0|[1-9]\d*)(?:\.\d+)?$""")
    put("description", description)
}

private fun enumSchema(values: List<String>, description: String) = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { values.forEach(::add) })
    put("description", description)
}

private class HealthMetricInputException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

private val SAVE_FIELDS = setOf("action", "source_quote", "measurements")
private val QUERY_FIELDS = setOf("action", "types", "limit")
private val DELETE_FIELDS = setOf("action", "source_quote", "record_ids")
private val MEASUREMENT_FIELDS = setOf("type", "value", "observed_at")
private const val DEFAULT_QUERY_LIMIT = 50
private const val MAX_QUERY_LIMIT = 100
private const val MAX_MEASUREMENTS_PER_SAVE = 20
private const val MAX_DELETE_COUNT = 100
private const val MAX_FUTURE_SKEW_MILLIS = 24 * 60 * 60 * 1_000L
private val EXPLICIT_NUMBER = Regex("""(?<![\d.])\d+(?:\.\d+)?(?![\d.])""")
