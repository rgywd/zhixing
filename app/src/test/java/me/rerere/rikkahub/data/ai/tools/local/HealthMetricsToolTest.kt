package me.rerere.rikkahub.data.ai.tools.local

import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.HealthMetricDraft
import me.rerere.rikkahub.data.model.HealthMetricRecord
import me.rerere.rikkahub.data.model.HealthMetricSourceType
import me.rerere.rikkahub.data.model.HealthMetricType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMetricsToolTest {
    @Test
    fun `schema exposes one strict action tool`() {
        val tool = HealthToolHarness().tool
        val schema = tool.parameters() as InputSchema.Obj

        assertEquals("health_metrics", tool.name)
        assertEquals(listOf("action"), schema.required)
        assertEquals(false, schema.additionalProperties)
        assertEquals(
            listOf("save", "query", "delete"),
            schema.properties.getValue("action").jsonObject
                .getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "string",
            schema.properties.getValue("measurements").jsonObject
                .getValue("items").jsonObject
                .getValue("properties").jsonObject
                .getValue("value").jsonObject
                .getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun `save accepts explicit canonical measurements and binds one source quote`() = runBlocking {
        val harness = HealthToolHarness()
        val result = harness.tool.execute(
            saveInput(
                measurements = listOf(
                    Triple("WEIGHT_KG", "72.40", "2026-08-17"),
                    Triple("BODY_FAT_PERCENT", "19.1", null),
                ),
            ),
        ).json()

        assertTrue(result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals("今天体重 72.4 kg，体脂率 19.1%", harness.sourceQuote)
        assertEquals(listOf("72.4", "19.1"), harness.savedDrafts?.map { it.valueDecimal })
        assertEquals("kg", result.getValue("records").jsonArray.first().jsonObject.getValue("unit").jsonPrimitive.content)
        assertTrue(harness.savedDrafts?.first()?.observedAtEpochMillis != null)
        assertNull(harness.savedDrafts?.last()?.observedAtEpochMillis)
    }

    @Test
    fun `save rejects unsupported metrics impossible values and numeric JSON`() = runBlocking {
        val invalid = listOf(
            saveInput(listOf(Triple("ESTIMATED_APPEARANCE", "18", null))),
            saveInput(listOf(Triple("BODY_FAT_PERCENT", "120", null))),
            saveInput(listOf(Triple("BMI", "21.5", null))),
            buildJsonObject {
                put("action", "save")
                put("source_quote", "体重 72.4 kg")
                put("measurements", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "WEIGHT_KG")
                        put("value", 72.4)
                    })
                })
            },
        )

        invalid.forEach { input ->
            val harness = HealthToolHarness()
            val result = harness.tool.execute(input).json()
            assertFalse(result.getValue("success").jsonPrimitive.content.toBoolean())
            assertNull(harness.savedDrafts)
        }
    }

    @Test
    fun `query respects AI health privacy setting`() = runBlocking {
        val disabled = HealthToolHarness(canRead = false).tool.execute(
            buildJsonObject { put("action", "query") },
        ).json()
        val enabled = HealthToolHarness(canRead = true).apply {
            records += sampleRecord()
        }.tool.execute(
            buildJsonObject {
                put("action", "query")
                put("types", buildJsonArray { add(JsonPrimitive("WEIGHT_KG")) })
                put("limit", 10)
            },
        ).json()

        assertEquals("HEALTH_DATA_DISABLED", disabled.errorCode())
        assertEquals("1", enabled.getValue("count").jsonPrimitive.content)
    }

    @Test
    fun `sanitizer strips unsupported nested fields`() {
        val raw = JsonObject(
            saveInput().toMutableMap().apply {
                put("private_note", Json.parseToJsonElement("\"do not persist\""))
                val item = getValue("measurements").jsonArray.single().jsonObject
                put("measurements", buildJsonArray {
                    add(JsonObject(item.toMutableMap().apply {
                        put("photo_analysis", Json.parseToJsonElement("\"estimated from photo\""))
                    }))
                })
            },
        )

        val sanitized = sanitizeHealthMetricsInputForStorage(raw.toString())

        assertFalse(sanitized.contains("private_note"))
        assertFalse(sanitized.contains("photo_analysis"))
        assertFalse(sanitized.contains("estimated from photo"))
        assertTrue(sanitized.contains("source_quote"))
    }

    private fun saveInput(
        measurements: List<Triple<String, String, String?>> = listOf(
            Triple("WEIGHT_KG", "72.4", null),
        ),
    ) = buildJsonObject {
        put("action", "save")
        put("source_quote", "今天体重 72.4 kg，体脂率 19.1%")
        put("measurements", buildJsonArray {
            measurements.forEach { (type, value, observedAt) ->
                add(buildJsonObject {
                    put("type", type)
                    put("value", value)
                    observedAt?.let { put("observed_at", it) }
                })
            }
        })
    }
}

private class HealthToolHarness(canRead: Boolean = true) {
    val records = mutableListOf<HealthMetricRecord>()
    var savedDrafts: List<HealthMetricDraft>? = null
    var sourceQuote: String? = null

    val tool = buildHealthMetricsTool(
        listRecent = { types, limit ->
            records.filter { types == null || it.type in types }.take(limit)
        },
        save = { drafts, quote ->
            savedDrafts = drafts
            sourceQuote = quote
            drafts.mapIndexed { index, draft ->
                sampleRecord(
                    id = "record-$index",
                    type = draft.type,
                    value = draft.valueDecimal,
                    observedAt = draft.observedAtEpochMillis,
                )
            }.also(records::addAll)
        },
        delete = { ids, _ ->
            val before = records.size
            records.removeAll { it.id in ids }
            before - records.size
        },
        canRead = { canRead },
        nowEpochMillis = { 1_786_982_400_000L },
        zoneId = ZoneId.of("Asia/Shanghai"),
    )
}

private fun sampleRecord(
    id: String = "weight-1",
    type: HealthMetricType = HealthMetricType.WEIGHT_KG,
    value: String = "72.4",
    observedAt: Long? = 1_776_571_200_000L,
) = HealthMetricRecord(
    id = id,
    type = type,
    valueDecimal = value,
    unit = type.canonicalUnit,
    observedAtEpochMillis = observedAt,
    recordedAtEpochMillis = 1_786_982_400_000L,
    sourceType = HealthMetricSourceType.AI_EXTRACTED_CHAT,
    sourceConversationId = "conversation-1",
    sourceMessageId = "message-1",
)

private fun List<UIMessagePart>.json(): JsonObject =
    Json.parseToJsonElement(filterIsInstance<UIMessagePart.Text>().single().text).jsonObject

private fun JsonObject.errorCode(): String =
    getValue("error").jsonObject.getValue("code").jsonPrimitive.content
