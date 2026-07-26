package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MonthlyLedgerAggregationMode
import me.rerere.rikkahub.data.model.MonthlyLedgerChannelDraft
import me.rerere.rikkahub.data.model.MonthlyLedgerChannelSummary
import me.rerere.rikkahub.data.model.MonthlyLedgerCoverage
import me.rerere.rikkahub.data.model.MonthlyLedgerSummary
import me.rerere.rikkahub.data.model.MonthlyLedgerSummaryDraft
import me.rerere.rikkahub.data.repository.MonthlyLedgerRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.util.Locale

private const val MONTHLY_SPENDING_TOOL_NAME = "monthly_spending_summary"
private val MONTH_PATTERN = Regex("""\d{4}-(0[1-9]|1[0-2])""")
private val CURRENCY_PATTERN = Regex("""[A-Z]{3}""")
private val CHANNEL_KEY_PATTERN = Regex("""[a-z0-9][a-z0-9_-]{0,47}""")
private val DECIMAL_AMOUNT_PATTERN = Regex("""\d+(?:\.\d+)?""")
private val SAVE_INPUT_FIELDS = setOf(
    "action",
    "month",
    "currency",
    "minor_unit",
    "total_expense",
    "total_income",
    "aggregation_mode",
    "coverage",
    "confidence_percent",
    "warnings",
    "channels",
)
private val READ_INPUT_FIELDS = setOf("action", "month", "currency")
private val CHANNEL_INPUT_FIELDS = setOf(
    "channel_key",
    "channel_name",
    "total_expense",
    "total_income",
    "coverage",
    "confidence_percent",
    "warnings",
)

internal fun buildMonthlySpendingSummaryTool(repository: MonthlyLedgerRepository): Tool =
    buildMonthlySpendingSummaryTool(
        getMonth = repository::getMonth,
        replace = repository::replace,
        delete = repository::delete,
    )

internal fun buildMonthlySpendingSummaryTool(
    getMonth: suspend (String) -> List<MonthlyLedgerSummary>,
    replace: suspend (MonthlyLedgerSummaryDraft) -> MonthlyLedgerSummary,
    delete: suspend (String, String?) -> Unit,
): Tool = Tool(
    name = MONTHLY_SPENDING_TOOL_NAME,
    description = """
        Save, query, or delete local monthly spending summaries in Zhixing.
        Before `save`, first analyze the images, text-based PDFs, or CSV files already attached to the current chat.
        This tool does not read or store attachments, transaction rows, or analysis intermediates. Scanned PDFs and
        XLS/XLSX files are not currently guaranteed to be understood correctly.
        Channel totals are raw per-channel summaries. When aggregation_mode is DEDUPED_ESTIMATE, the monthly totals
        may differ from the sum of channel totals because the model has removed likely cross-channel duplicates.
        Amounts must be non-negative decimal strings in major currency units. minor_unit controls exact conversion to
        integer minor units; never pass JSON floating-point numbers. `save` replaces one month/currency atomically.
        `query` requires month and may optionally filter currency. `delete` is idempotent and requires month.
    """.trimIndent().replace("\n", " "),
    parameters = { monthlySpendingInputSchema() },
    sanitizeInputForStorage = ::sanitizeMonthlySpendingInputForStorage,
    needsApproval = { input ->
        val action = (input as? JsonObject)
            ?.get("action")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.trim()
            ?.lowercase(Locale.ROOT)
        action != "query"
    },
    execute = { input ->
        try {
            val params = input as? JsonObject
                ?: throw MonthlySpendingInputException("INVALID_INPUT", "tool input must be a JSON object")
            val action = params.requiredString("action").lowercase(Locale.ROOT)
            val payload = when (action) {
                "save" -> executeSave(params, replace)
                "query" -> executeQuery(params, getMonth)
                "delete" -> executeDelete(params, getMonth, delete)
                else -> throw MonthlySpendingInputException(
                    "UNKNOWN_ACTION",
                    "action must be one of [save, query, delete]",
                )
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } catch (error: CancellationException) {
            throw error
        } catch (error: MonthlySpendingInputException) {
            monthlySpendingError(error.code, error.message.orEmpty())
        } catch (error: IllegalArgumentException) {
            monthlySpendingError("INVALID_INPUT", error.message ?: "invalid monthly spending summary")
        } catch (_: Exception) {
            monthlySpendingError("EXECUTION_FAILED", "monthly spending summary operation failed")
        }
    },
)

internal fun sanitizeMonthlySpendingInputForStorage(input: String): String {
    val params = runCatching {
        Json.parseToJsonElement(input.ifBlank { "{}" }) as? JsonObject
    }.getOrNull() ?: return "{}"
    val action = (params["action"] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.lowercase(Locale.ROOT)
    val allowedFields = when (action) {
        "save" -> SAVE_INPUT_FIELDS
        "query", "delete" -> READ_INPUT_FIELDS
        else -> READ_INPUT_FIELDS
    }
    return buildJsonObject {
        params.forEach paramsLoop@{ (key, value) ->
            if (key !in allowedFields) return@paramsLoop
            if (key == "channels") {
                val channels = value as? JsonArray ?: return@paramsLoop
                put(
                    key,
                    buildJsonArray {
                        channels.forEach channelsLoop@{ channelValue ->
                            val channel = channelValue as? JsonObject ?: return@channelsLoop
                            add(
                                buildJsonObject {
                                    channel.forEach { (channelKey, channelFieldValue) ->
                                        if (channelKey in CHANNEL_INPUT_FIELDS) {
                                            put(channelKey, channelFieldValue)
                                        }
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

private fun monthlySpendingInputSchema() = InputSchema.Obj(
    properties = buildJsonObject {
        put("action", enumSchema(listOf("save", "query", "delete"), "Operation to perform."))
        put("month", stringSchema("Month in YYYY-MM format. Required for every action."))
        put("currency", stringSchema("Three-letter uppercase ISO 4217 code. Required for save; optional filter otherwise."))
        put("minor_unit", integerSchema("Currency decimal places from 0 to 6. Required for save."))
        put("total_expense", decimalStringSchema("Monthly expense in major currency units. Required for save."))
        put("total_income", decimalStringSchema("Monthly income in major currency units. Required for save."))
        put(
            "aggregation_mode",
            enumSchema(
                MonthlyLedgerAggregationMode.entries.map { it.name },
                "RAW_CHANNEL_SUM requires monthly totals to equal channel sums; DEDUPED_ESTIMATE may differ.",
            ),
        )
        put("coverage", coverageSchema("Coverage of the complete monthly summary. Required for save."))
        put("confidence_percent", integerSchema("Integer confidence from 0 to 100. Required for save."))
        put("warnings", stringArraySchema("Optional warnings about incomplete or uncertain analysis."))
        put(
            "channels",
            buildJsonObject {
                put("type", "array")
                put("minItems", 1)
                put("description", "Raw per-channel monthly summaries. Required for save.")
                put(
                    "items",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "channel_key",
                                    stringSchema(
                                        "Stable lowercase key using letters, digits, underscores, or hyphens.",
                                    ),
                                )
                                put("channel_name", stringSchema("Human-readable channel name."))
                                put(
                                    "total_expense",
                                    decimalStringSchema("Channel expense in major currency units."),
                                )
                                put(
                                    "total_income",
                                    decimalStringSchema("Channel income in major currency units."),
                                )
                                put("coverage", coverageSchema("Coverage of this channel summary."))
                                put(
                                    "confidence_percent",
                                    integerSchema("Integer confidence from 0 to 100."),
                                )
                                put("warnings", stringArraySchema("Optional warnings for this channel."))
                            },
                        )
                        put(
                            "required",
                            buildJsonArray {
                                add("channel_key")
                                add("channel_name")
                                add("total_expense")
                                add("total_income")
                                add("coverage")
                                add("confidence_percent")
                            },
                        )
                        put("additionalProperties", false)
                    },
                )
            },
        )
    },
    required = listOf("action", "month"),
    additionalProperties = false,
)

private suspend fun executeSave(
    params: JsonObject,
    replace: suspend (MonthlyLedgerSummaryDraft) -> MonthlyLedgerSummary,
): JsonObject {
    params.requireOnlyKeys(SAVE_INPUT_FIELDS, "save")
    val month = params.validMonth()
    requireInput(YearMonth.parse(month) <= YearMonth.now(), "save month must not be in the future")
    val currency = params.requiredString("currency")
    requireInput(CURRENCY_PATTERN.matches(currency), "currency must be a three-letter uppercase ISO 4217 code")
    val minorUnit = params.requiredInt("minor_unit")
    requireInput(minorUnit in 0..6, "minor_unit must be between 0 and 6")
    val aggregationMode = params.requiredEnum<MonthlyLedgerAggregationMode>("aggregation_mode")
    val channelsArray = params["channels"] as? JsonArray
        ?: throw MonthlySpendingInputException("INVALID_INPUT", "channels must be an array")
    requireInput(channelsArray.isNotEmpty(), "channels must contain at least one item")

    val channelKeys = mutableSetOf<String>()
    val channels = channelsArray.mapIndexed { index, element ->
        val channel = element as? JsonObject
            ?: throw MonthlySpendingInputException("INVALID_INPUT", "channels[$index] must be an object")
        channel.requireOnlyKeys(CHANNEL_INPUT_FIELDS, "channels[$index]")
        val channelKey = channel.requiredString("channel_key")
        requireInput(
            CHANNEL_KEY_PATTERN.matches(channelKey),
            "channels[$index].channel_key must use lowercase letters, digits, underscores, or hyphens",
        )
        requireInput(channelKeys.add(channelKey), "channel_key must be unique within a monthly summary")
        MonthlyLedgerChannelDraft(
            channelKey = channelKey,
            channelName = channel.requiredString("channel_name"),
            expenseMinor = channel.requiredAmountMinor("total_expense", minorUnit),
            incomeMinor = channel.requiredAmountMinor("total_income", minorUnit),
            coverage = channel.requiredEnum("coverage"),
            confidencePercent = channel.requiredConfidence("confidence_percent"),
            warnings = channel.optionalWarnings("warnings"),
        )
    }

    val expenseMinor = params.requiredAmountMinor("total_expense", minorUnit)
    val incomeMinor = params.requiredAmountMinor("total_income", minorUnit)
    if (aggregationMode == MonthlyLedgerAggregationMode.RAW_CHANNEL_SUM) {
        requireInput(
            expenseMinor == sumExactly(channels.map { it.expenseMinor }, "channel expense"),
            "RAW_CHANNEL_SUM total_expense must equal the sum of channel expenses",
        )
        requireInput(
            incomeMinor == sumExactly(channels.map { it.incomeMinor }, "channel income"),
            "RAW_CHANNEL_SUM total_income must equal the sum of channel incomes",
        )
    }

    val summary = replace(
        MonthlyLedgerSummaryDraft(
            month = month,
            currency = currency,
            minorUnit = minorUnit,
            expenseMinor = expenseMinor,
            incomeMinor = incomeMinor,
            aggregationMode = aggregationMode,
            coverage = params.requiredEnum("coverage"),
            confidencePercent = params.requiredConfidence("confidence_percent"),
            warnings = params.optionalWarnings("warnings"),
            channels = channels,
        ),
    )
    return buildJsonObject {
        put("success", true)
        put("action", "save")
        put("summary", summary.toToolJson())
    }
}

private suspend fun executeQuery(
    params: JsonObject,
    getMonth: suspend (String) -> List<MonthlyLedgerSummary>,
): JsonObject {
    params.requireOnlyKeys(READ_INPUT_FIELDS, "query")
    val month = params.validMonth()
    val currency = params.optionalCurrency()
    val summaries = getMonth(month)
        .filter { currency == null || it.currency == currency }
        .sortedBy { it.currency }
    return buildJsonObject {
        put("success", true)
        put("action", "query")
        put("count", summaries.size)
        put("summaries", buildJsonArray { summaries.forEach { add(it.toToolJson()) } })
    }
}

private suspend fun executeDelete(
    params: JsonObject,
    getMonth: suspend (String) -> List<MonthlyLedgerSummary>,
    delete: suspend (String, String?) -> Unit,
): JsonObject {
    params.requireOnlyKeys(READ_INPUT_FIELDS, "delete")
    val month = params.validMonth()
    val currency = params.optionalCurrency()
    val deleted = getMonth(month).any { currency == null || it.currency == currency }
    delete(month, currency)
    return buildJsonObject {
        put("success", true)
        put("action", "delete")
        put("month", month)
        put("currency", currency?.let(::JsonPrimitive) ?: JsonNull)
        put("deleted", deleted)
    }
}

private fun JsonObject.validMonth(): String {
    val month = requiredString("month")
    requireInput(MONTH_PATTERN.matches(month), "month must use YYYY-MM format")
    return month
}

private fun JsonObject.requireOnlyKeys(allowed: Set<String>, context: String) {
    requireInput(keys.all { it in allowed }, "$context input contains unsupported fields")
}

private fun JsonObject.optionalCurrency(): String? {
    val value = this["currency"] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw MonthlySpendingInputException("INVALID_INPUT", "currency must be a string")
    requireInput(primitive.isString, "currency must be a string")
    val currency = primitive.content.trim()
    requireInput(CURRENCY_PATTERN.matches(currency), "currency must be a three-letter uppercase ISO 4217 code")
    return currency
}

private fun JsonObject.requiredString(name: String): String {
    val primitive = this[name] as? JsonPrimitive
        ?: throw MonthlySpendingInputException("INVALID_INPUT", "$name is required and must be a string")
    requireInput(primitive.isString, "$name must be a string")
    val value = primitive.content.trim()
    requireInput(value.isNotBlank(), "$name must not be blank")
    return value
}

private fun JsonObject.requiredInt(name: String): Int {
    val primitive = this[name] as? JsonPrimitive
        ?: throw MonthlySpendingInputException("INVALID_INPUT", "$name is required and must be an integer")
    requireInput(!primitive.isString, "$name must be an integer")
    return primitive.intOrNull
        ?: throw MonthlySpendingInputException("INVALID_INPUT", "$name must be an integer")
}

private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(name: String): T {
    val value = requiredString(name)
    return enumValues<T>().firstOrNull { it.name == value }
        ?: throw MonthlySpendingInputException(
            "INVALID_INPUT",
            "$name must be one of [${enumValues<T>().joinToString { it.name }}]",
        )
}

private fun JsonObject.requiredConfidence(name: String): Int =
    requiredInt(name).also { requireInput(it in 0..100, "$name must be between 0 and 100") }

private fun JsonObject.requiredAmountMinor(name: String, minorUnit: Int): Long {
    val primitive = this[name] as? JsonPrimitive
        ?: throw MonthlySpendingInputException("INVALID_INPUT", "$name is required and must be a decimal string")
    requireInput(primitive.isString, "$name must be a decimal string")
    val amount = primitive.content.trim()
    requireInput(DECIMAL_AMOUNT_PATTERN.matches(amount), "$name must be a non-negative decimal string")
    val fractionDigits = amount.substringAfter('.', "").length
    requireInput(
        fractionDigits <= minorUnit,
        "$name has more fractional digits than minor_unit allows",
    )
    return try {
        BigDecimal(amount)
            .movePointRight(minorUnit)
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact()
    } catch (_: ArithmeticException) {
        throw MonthlySpendingInputException("AMOUNT_OUT_OF_RANGE", "$name exceeds the supported Long range")
    } catch (_: NumberFormatException) {
        throw MonthlySpendingInputException("INVALID_INPUT", "$name must be a decimal string")
    }
}

private fun JsonObject.optionalWarnings(name: String): List<String> {
    val value = this[name] ?: return emptyList()
    val array = value as? JsonArray
        ?: throw MonthlySpendingInputException("INVALID_INPUT", "$name must be an array of strings")
    return array.mapIndexed { index, element ->
        val warning = element as? JsonPrimitive
            ?: throw MonthlySpendingInputException("INVALID_INPUT", "$name[$index] must be a string")
        requireInput(warning.isString, "$name[$index] must be a string")
        warning.content.trim().also { requireInput(it.isNotBlank(), "$name[$index] must not be blank") }
    }
}

private fun sumExactly(values: List<Long>, label: String): Long =
    try {
        values.fold(0L, Math::addExact)
    } catch (_: ArithmeticException) {
        throw MonthlySpendingInputException("AMOUNT_OUT_OF_RANGE", "$label sum exceeds the supported Long range")
    }

private fun MonthlyLedgerSummary.toToolJson() = buildJsonObject {
    put("month", month)
    put("currency", currency)
    put("minor_unit", minorUnit)
    put("total_expense", formatMinorAmount(expenseMinor, minorUnit))
    put("total_income", formatMinorAmount(incomeMinor, minorUnit))
    put("aggregation_mode", aggregationMode.name)
    put("coverage", coverage.name)
    put("confidence_percent", confidencePercent)
    put("warnings", buildJsonArray { warnings.forEach(::add) })
    put(
        "channels",
        buildJsonArray {
            channels.sortedBy(MonthlyLedgerChannelSummary::channelKey).forEach { channel ->
                add(
                    buildJsonObject {
                        put("channel_key", channel.channelKey)
                        put("channel_name", channel.channelName)
                        put("total_expense", formatMinorAmount(channel.expenseMinor, minorUnit))
                        put("total_income", formatMinorAmount(channel.incomeMinor, minorUnit))
                        put("coverage", channel.coverage.name)
                        put("confidence_percent", channel.confidencePercent)
                        put("warnings", buildJsonArray { channel.warnings.forEach(::add) })
                    },
                )
            }
        },
    )
    put("updated_at_epoch_ms", updatedAt)
}

private fun formatMinorAmount(value: Long, minorUnit: Int): String =
    BigDecimal.valueOf(value, minorUnit).toPlainString()

private fun monthlySpendingError(code: String, message: String): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("success", false)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                    },
                )
            }.toString(),
        ),
    )

private fun requireInput(condition: Boolean, message: String) {
    if (!condition) throw MonthlySpendingInputException("INVALID_INPUT", message)
}

private class MonthlySpendingInputException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

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
    put("pattern", """^\d+(?:\.\d+)?$""")
    put("description", description)
}

private fun enumSchema(values: List<String>, description: String) = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { values.forEach(::add) })
    put("description", description)
}

private fun coverageSchema(description: String) =
    enumSchema(MonthlyLedgerCoverage.entries.map { it.name }, description)

private fun stringArraySchema(description: String) = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject { put("type", "string") })
    put("description", description)
}
