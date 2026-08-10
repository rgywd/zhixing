package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MonthlyLedgerAggregationMode
import me.rerere.rikkahub.data.model.MonthlyLedgerChannelDraft
import me.rerere.rikkahub.data.model.MonthlyLedgerChannelSummary
import me.rerere.rikkahub.data.model.MonthlyLedgerCoverage
import me.rerere.rikkahub.data.model.MonthlyLedgerSummary
import me.rerere.rikkahub.data.model.MonthlyLedgerSummaryDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlySpendingSummaryToolTest {
    @Test
    fun `one action tool exposes the frozen schema and legacy approval matrix`() {
        val tool = ToolHarness().tool

        assertEquals("monthly_spending_summary", tool.name)
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(listOf("action", "month"), schema.required)
        assertEquals(false, schema.additionalProperties)
        assertEquals(
            "false",
            Json.encodeToJsonElement(InputSchema.serializer(), schema).jsonObject
                .getValue("additionalProperties").jsonPrimitive.content,
        )
        assertEquals(
            listOf("save", "query", "delete"),
            schema.properties.getValue("action").jsonObject
                .getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("string", schema.properties.getValue("total_expense").jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("string", schema.properties.getValue("total_income").jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("integer", schema.properties.getValue("minor_unit").jsonObject["type"]?.jsonPrimitive?.content)

        val channelSchema = schema.properties.getValue("channels").jsonObject
            .getValue("items").jsonObject
        assertEquals(
            "false",
            channelSchema.getValue("additionalProperties").jsonPrimitive.content,
        )
        assertEquals(
            setOf(
                "channel_key",
                "channel_name",
                "total_expense",
                "total_income",
                "coverage",
                "confidence_percent",
            ),
            channelSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )

        assertFalse(tool.needsApproval(actionInput("query")))
        assertFalse(tool.needsApproval(actionInput("QuErY")))
        assertTrue(tool.needsApproval(actionInput("save")))
        assertTrue(tool.needsApproval(actionInput("delete")))
        assertTrue(tool.needsApproval(actionInput("future_action")))
        assertTrue(tool.needsApproval(buildJsonObject {}))
    }

    @Test
    fun `save converts decimal strings to exact Long minor units`() = runBlocking {
        val harness = ToolHarness()
        val result = harness.tool.execute(
            saveInput(
                totalExpense = "92233720368547758.07",
                channelExpense = "92233720368547758.07",
            ),
        ).json()

        assertTrue(result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(Long.MAX_VALUE, harness.savedDraft?.expenseMinor)
        assertEquals(0L, harness.savedDraft?.incomeMinor)
        assertEquals(
            "92233720368547758.07",
            result.getValue("summary").jsonObject.getValue("total_expense").jsonPrimitive.content,
        )
    }

    @Test
    fun `deduped estimate allows monthly totals to differ from raw channel sums`() = runBlocking {
        val harness = ToolHarness()
        val result = harness.tool.execute(
            saveInput(
                aggregationMode = "DEDUPED_ESTIMATE",
                totalExpense = "80.00",
                channels = listOf(
                    ChannelInput("wechat", "微信", "60.00"),
                    ChannelInput("credit_card", "信用卡", "40.00"),
                ),
            ),
        ).json()

        assertTrue(result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(8_000L, harness.savedDraft?.expenseMinor)
        assertEquals(10_000L, harness.savedDraft?.channels?.sumOf { it.expenseMinor })
        assertEquals(MonthlyLedgerAggregationMode.DEDUPED_ESTIMATE, harness.savedDraft?.aggregationMode)
    }

    @Test
    fun `save rejects invalid boundaries before calling repository`() = runBlocking {
        val invalidInputs = listOf(
            saveInput(month = "2026-13"),
            saveInput(month = "9999-12"),
            saveInput(currency = "CN1"),
            saveInput(totalExpense = "1.001", channelExpense = "1.001"),
            saveInput(
                totalExpense = "2.00",
                channels = listOf(
                    ChannelInput("wechat", "微信", "1.00"),
                    ChannelInput("wechat", "微信副本", "1.00"),
                ),
            ),
            saveInput(totalExpense = "9.99", channelExpense = "10.00"),
            saveInput(
                totalExpense = "92233720368547758.08",
                channelExpense = "92233720368547758.08",
            ),
        )

        invalidInputs.forEach { input ->
            val harness = ToolHarness()
            val result = harness.tool.execute(input).json()
            assertFalse(result.getValue("success").jsonPrimitive.content.toBoolean())
            assertTrue(result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content.isNotBlank())
            assertNull(harness.savedDraft)
        }
    }

    @Test
    fun `save rejects unsupported top-level and channel fields`() = runBlocking {
        val base = saveInput()
        val topLevelExtra = JsonObject(
            base.toMutableMap().apply {
                put("transactions", buildJsonArray {})
            },
        )
        val channelExtra = JsonObject(
            base.toMutableMap().apply {
                val channel = base.getValue("channels").jsonArray.single().jsonObject
                put(
                    "channels",
                    JsonArray(
                        listOf(
                            JsonObject(
                                channel.toMutableMap().apply {
                                    put("raw_text", Json.parseToJsonElement("\"private source text\""))
                                },
                            ),
                        ),
                    ),
                )
            },
        )

        listOf(topLevelExtra, channelExtra).forEach { input ->
            val harness = ToolHarness()
            val result = harness.tool.execute(input).json()
            assertFalse(result.getValue("success").jsonPrimitive.content.toBoolean())
            assertEquals(
                "INVALID_INPUT",
                result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
            )
            assertNull(harness.savedDraft)
        }
    }

    @Test
    fun `storage sanitizer removes unsupported top-level and channel fields`() {
        val input = JsonObject(
            saveInput().toMutableMap().apply {
                put("raw_text", Json.parseToJsonElement("\"private bill text\""))
                val channel = getValue("channels").jsonArray.single().jsonObject
                put(
                    "channels",
                    JsonArray(
                        listOf(
                            JsonObject(
                                channel.toMutableMap().apply {
                                    put("transactions", buildJsonArray {
                                        add(Json.parseToJsonElement("\"private transaction\""))
                                    })
                                },
                            ),
                        ),
                    ),
                )
            },
        )

        val sanitizedText = sanitizeMonthlySpendingInputForStorage(input.toString())
        val sanitized = Json.parseToJsonElement(sanitizedText).jsonObject
        val sanitizedChannel = sanitized.getValue("channels").jsonArray.single().jsonObject

        assertFalse(sanitized.containsKey("raw_text"))
        assertFalse(sanitizedChannel.containsKey("transactions"))
        assertFalse(sanitizedText.contains("private bill text"))
        assertFalse(sanitizedText.contains("private transaction"))
        assertEquals("10.00", sanitized.getValue("total_expense").jsonPrimitive.content)
        assertEquals("wechat", sanitizedChannel.getValue("channel_key").jsonPrimitive.content)
    }

    @Test
    fun `raw channel sum validates both expense and income`() = runBlocking {
        val expenseMismatch = ToolHarness().tool.execute(
            saveInput(totalExpense = "9.99", channelExpense = "10.00"),
        ).json()
        val incomeMismatch = ToolHarness().tool.execute(
            saveInput(totalIncome = "1.00", channelIncome = "0.00"),
        ).json()

        assertFalse(expenseMismatch.getValue("success").jsonPrimitive.content.toBoolean())
        assertTrue(
            expenseMismatch.getValue("error").jsonObject
                .getValue("message").jsonPrimitive.content.contains("total_expense"),
        )
        assertFalse(incomeMismatch.getValue("success").jsonPrimitive.content.toBoolean())
        assertTrue(
            incomeMismatch.getValue("error").jsonObject
                .getValue("message").jsonPrimitive.content.contains("total_income"),
        )
    }

    @Test
    fun `query requires month and optionally filters currency`() = runBlocking {
        val harness = ToolHarness()
        harness.records += sampleSummary(currency = "CNY", expenseMinor = 1_234L)
        harness.records += sampleSummary(currency = "USD", expenseMinor = 500L)

        val filtered = harness.tool.execute(
            buildJsonObject {
                put("action", "QuErY")
                put("month", "2026-07")
                put("currency", "CNY")
            },
        ).json()
        val missingMonth = harness.tool.execute(actionInput("query")).json()

        assertEquals("1", filtered.getValue("count").jsonPrimitive.content)
        assertEquals(
            "12.34",
            filtered.getValue("summaries").jsonArray.single().jsonObject
                .getValue("total_expense").jsonPrimitive.content,
        )
        assertFalse(missingMonth.getValue("success").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `delete is approved and idempotently reports whether anything existed`() = runBlocking {
        val harness = ToolHarness()
        harness.records += sampleSummary()
        val input = buildJsonObject {
            put("action", "delete")
            put("month", "2026-07")
            put("currency", "CNY")
        }

        val first = harness.tool.execute(input).json()
        val second = harness.tool.execute(input).json()

        assertTrue(first.getValue("deleted").jsonPrimitive.content.toBoolean())
        assertFalse(second.getValue("deleted").jsonPrimitive.content.toBoolean())
        assertTrue(harness.records.isEmpty())
        assertEquals(
            listOf("2026-07" to "CNY", "2026-07" to "CNY"),
            harness.deleteCalls,
        )
    }

    @Test
    fun `unexpected repository failures return a stable redacted error`() = runBlocking {
        val tool = buildMonthlySpendingSummaryTool(
            getMonth = { error("database path and private details") },
            replace = { error("database path and private details") },
            delete = { _, _ -> error("database path and private details") },
        )

        val result = tool.execute(
            buildJsonObject {
                put("action", "query")
                put("month", "2026-07")
            },
        ).json()
        val serialized = result.toString()

        assertEquals(
            "EXECUTION_FAILED",
            result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
        )
        assertFalse(serialized.contains("database path"))
        assertFalse(serialized.contains("MonthlySpendingSummaryTool"))
    }

    private fun actionInput(action: String) = buildJsonObject { put("action", action) }
}

private data class ChannelInput(
    val key: String,
    val name: String,
    val expense: String,
    val income: String = "0.00",
)

private fun saveInput(
    month: String = "2026-07",
    currency: String = "CNY",
    aggregationMode: String = "RAW_CHANNEL_SUM",
    totalExpense: String = "10.00",
    totalIncome: String = "0.00",
    channelExpense: String = totalExpense,
    channelIncome: String = totalIncome,
    channels: List<ChannelInput> = listOf(ChannelInput("wechat", "微信", channelExpense, channelIncome)),
): JsonObject = buildJsonObject {
    put("action", "save")
    put("month", month)
    put("currency", currency)
    put("minor_unit", 2)
    put("total_expense", totalExpense)
    put("total_income", totalIncome)
    put("aggregation_mode", aggregationMode)
    put("coverage", "FULL")
    put("confidence_percent", 95)
    put("warnings", buildJsonArray {})
    put(
        "channels",
        buildJsonArray {
            channels.forEach { channel ->
                add(
                    buildJsonObject {
                        put("channel_key", channel.key)
                        put("channel_name", channel.name)
                        put("total_expense", channel.expense)
                        put("total_income", channel.income)
                        put("coverage", "FULL")
                        put("confidence_percent", 90)
                        put("warnings", buildJsonArray {})
                    },
                )
            }
        },
    )
}

private class ToolHarness {
    val records = mutableListOf<MonthlyLedgerSummary>()
    val deleteCalls = mutableListOf<Pair<String, String?>>()
    var savedDraft: MonthlyLedgerSummaryDraft? = null

    val tool = buildMonthlySpendingSummaryTool(
        getMonth = { month -> records.filter { it.month == month } },
        replace = { draft ->
            savedDraft = draft
            draft.toSummary().also { summary ->
                records.removeAll { it.month == summary.month && it.currency == summary.currency }
                records += summary
            }
        },
        delete = { month, currency ->
            deleteCalls += month to currency
            records.removeAll { it.month == month && (currency == null || it.currency == currency) }
        },
    )
}

private fun MonthlyLedgerSummaryDraft.toSummary() = MonthlyLedgerSummary(
    id = "$month|$currency",
    month = month,
    currency = currency,
    minorUnit = minorUnit,
    expenseMinor = expenseMinor,
    incomeMinor = incomeMinor,
    aggregationMode = aggregationMode,
    coverage = coverage,
    confidencePercent = confidencePercent,
    warnings = warnings,
    channels = channels.map(MonthlyLedgerChannelDraft::toSummary),
    updatedAt = 1_800_000_000_000L,
)

private fun MonthlyLedgerChannelDraft.toSummary() = MonthlyLedgerChannelSummary(
    channelKey = channelKey,
    channelName = channelName,
    expenseMinor = expenseMinor,
    incomeMinor = incomeMinor,
    coverage = coverage,
    confidencePercent = confidencePercent,
    warnings = warnings,
)

private fun sampleSummary(
    currency: String = "CNY",
    expenseMinor: Long = 1_000L,
) = MonthlyLedgerSummary(
    id = "2026-07|$currency",
    month = "2026-07",
    currency = currency,
    minorUnit = 2,
    expenseMinor = expenseMinor,
    incomeMinor = 0L,
    aggregationMode = MonthlyLedgerAggregationMode.RAW_CHANNEL_SUM,
    coverage = MonthlyLedgerCoverage.FULL,
    confidencePercent = 95,
    warnings = emptyList(),
    channels = listOf(
        MonthlyLedgerChannelSummary(
            channelKey = "wechat",
            channelName = "微信",
            expenseMinor = expenseMinor,
            incomeMinor = 0L,
            coverage = MonthlyLedgerCoverage.FULL,
            confidencePercent = 90,
            warnings = emptyList(),
        ),
    ),
    updatedAt = 1_800_000_000_000L,
)

private fun List<UIMessagePart>.json(): JsonObject =
    Json.parseToJsonElement((single() as UIMessagePart.Text).text).jsonObject
