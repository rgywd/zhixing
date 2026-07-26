package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.life.INFORMATION_MONITOR_SCHEMA
import me.rerere.rikkahub.data.life.InformationMonitorChannel
import me.rerere.rikkahub.data.life.InformationMonitorDigestChannel
import me.rerere.rikkahub.data.life.InformationMonitorDigestEnvelope
import me.rerere.rikkahub.data.life.InformationMonitorFreshness
import me.rerere.rikkahub.data.life.InformationMonitorImportance
import me.rerere.rikkahub.data.life.InformationMonitorItem
import me.rerere.rikkahub.data.life.InformationMonitorItemsEnvelope
import me.rerere.rikkahub.data.life.InformationMonitorQuery
import me.rerere.rikkahub.data.life.InformationMonitorSnapshot
import me.rerere.rikkahub.data.life.InformationMonitorSourceState
import me.rerere.rikkahub.data.life.InformationMonitorSourceStatus
import me.rerere.rikkahub.data.life.InformationMonitorStatusEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxMonitorToolTest {
    @Test
    fun `tool exposes a frozen read-only schema without approval`() {
        val tool = Harness().tool
        val schema = tool.parameters() as InputSchema.Obj

        assertEquals("inbox_monitor", tool.name)
        assertEquals(listOf("action"), schema.required)
        assertEquals(false, schema.additionalProperties)
        assertEquals(
            listOf("status", "list", "digest"),
            schema.properties.getValue("action").jsonObject
                .getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("email", "feishu"),
            schema.properties.getValue("channel").jsonObject
                .getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("low", "normal", "high", "urgent"),
            schema.properties.getValue("minImportance").jsonObject
                .getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(tool.needsApproval(buildJsonObject { put("action", "status") }))
        assertFalse(tool.needsApproval(buildJsonObject { put("action", "list") }))
        assertFalse(tool.needsApproval(buildJsonObject { put("action", "digest") }))
    }

    @Test
    fun `each action routes the validated query and returns only typed summary fields`() = runBlocking {
        val harness = Harness()
        val input = buildJsonObject {
            put("action", "list")
            put("channel", "email")
            put("hours", 24)
            put("limit", 10)
            put("minImportance", "high")
        }

        val result = harness.tool.execute(input).json()

        assertEquals(
            InformationMonitorQuery(
                channel = InformationMonitorChannel.EMAIL,
                hours = 24,
                limit = 10,
                minImportance = InformationMonitorImportance.HIGH,
            ),
            harness.itemsQuery,
        )
        assertTrue(result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals("list", result.getValue("action").jsonPrimitive.content)
        assertEquals("fresh", result.getValue("freshness").jsonPrimitive.content)
        assertFalse("warning" in result)
        val data = result.getValue("data").jsonObject
        assertEquals(INFORMATION_MONITOR_SCHEMA, data.getValue("schema").jsonPrimitive.content)
        val item = data.getValue("items").jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "id",
                "sourceLabel",
                "channel",
                "occurredAt",
                "sender",
                "title",
                "summary",
                "actionItems",
                "importance",
            ),
            item.keys,
        )
        assertTrue(FORBIDDEN_RESPONSE_KEYS.none { it in result.toString() })

        harness.tool.execute(buildJsonObject { put("action", "status") })
        assertEquals(InformationMonitorQuery(), harness.statusQuery)
        harness.tool.execute(buildJsonObject { put("action", "digest") })
        assertEquals(InformationMonitorQuery(), harness.digestQuery)
    }

    @Test
    fun `invalid input fails before any remote call`() = runBlocking {
        val invalidInputs = listOf(
            buildJsonObject {},
            buildJsonObject { put("action", "unknown") },
            buildJsonObject { put("action", "list"); put("channel", "calendar") },
            buildJsonObject { put("action", "list"); put("hours", 0) },
            buildJsonObject { put("action", "list"); put("hours", 169) },
            buildJsonObject { put("action", "list"); put("limit", "10") },
            buildJsonObject { put("action", "list"); put("limit", 51) },
            buildJsonObject { put("action", "list"); put("raw", "private") },
            buildJsonObject { put("action", "status"); put("hours", 24) },
            buildJsonObject { put("action", "status"); put("limit", 10) },
            buildJsonObject { put("action", "status"); put("minImportance", "high") },
            buildJsonObject { put("action", "digest"); put("limit", 10) },
        )

        invalidInputs.forEach { input ->
            val harness = Harness()
            val result = harness.tool.execute(input).json()
            assertFalse(result.getValue("success").jsonPrimitive.content.toBoolean())
            assertTrue(result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content.isNotBlank())
            assertEquals(0, harness.callCount)
        }
    }

    @Test
    fun `stale snapshot is explicitly labelled for the model`() = runBlocking {
        val harness = Harness(freshness = InformationMonitorFreshness.STALE)

        val result = harness.tool.execute(buildJsonObject { put("action", "digest") }).json()

        assertTrue(result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals("stale", result.getValue("freshness").jsonPrimitive.content)
        assertEquals(
            "This is a last-known-good snapshot and may be up to five minutes old; do not present it as real-time.",
            result.getValue("warning").jsonPrimitive.content,
        )
        assertEquals(INFORMATION_MONITOR_SCHEMA, result.getValue("data").jsonObject
            .getValue("schema").jsonPrimitive.content)
    }

    @Test
    fun `storage sanitizer canonicalizes valid input and fails closed without retaining suspicious values`() {
        val valid = sanitizeInboxMonitorInputForStorage(
            buildJsonObject {
                put("action", "LiSt")
                put("channel", "FEISHU")
                put("hours", 8)
                put("limit", 12)
                put("minImportance", "HIGH")
            }.toString(),
        )
        val validParsed = Json.parseToJsonElement(valid).jsonObject
        assertEquals(
            setOf("action", "channel", "hours", "limit", "minImportance"),
            validParsed.keys,
        )
        assertEquals("list", validParsed.getValue("action").jsonPrimitive.content)
        assertEquals("feishu", validParsed.getValue("channel").jsonPrimitive.content)
        assertEquals("high", validParsed.getValue("minImportance").jsonPrimitive.content)

        val suspicious = sanitizeInboxMonitorInputForStorage(
            buildJsonObject {
                put("action", "list")
                put("channel", "feishu")
                put("hours", 8)
                put("body", "private body")
                put("credentials", "private token")
                put("providerItemId", "provider id")
            }.toString(),
        )

        assertEquals("{}", suspicious)
        assertFalse(suspicious.contains("private body"))
        assertFalse(suspicious.contains("private token"))
        assertFalse(suspicious.contains("provider id"))
        assertEquals("{}", sanitizeInboxMonitorInputForStorage("not json"))
        assertEquals(
            "{}",
            sanitizeInboxMonitorInputForStorage(
                """{"action":"status","channel":{"credentials":"private token"}}""",
            ),
        )
    }

    @Test
    fun `unconfigured and service errors are stable and do not expose exception details`() = runBlocking {
        val unconfigured = Harness(configured = false)
        val missing = unconfigured.tool.execute(buildJsonObject { put("action", "status") }).json()
        assertEquals(
            "NOT_CONFIGURED",
            missing.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
        )
        assertEquals(0, unconfigured.callCount)

        val failing = Harness(failure = IllegalStateException("secret body and credential"))
        val failure = failing.tool.execute(buildJsonObject { put("action", "digest") }).json()
        assertEquals(
            "SERVICE_UNAVAILABLE",
            failure.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
        )
        assertFalse(failure.toString().contains("secret"))
        assertFalse(failure.toString().contains("credential"))
    }

    @Test
    fun `cancellation propagates`() {
        val harness = Harness(failure = CancellationException("cancelled"))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                harness.tool.execute(buildJsonObject { put("action", "status") })
            }
        }
    }

    private class Harness(
        configured: Boolean = true,
        private val failure: Throwable? = null,
        private val freshness: InformationMonitorFreshness = InformationMonitorFreshness.FRESH,
    ) {
        var statusQuery: InformationMonitorQuery? = null
        var itemsQuery: InformationMonitorQuery? = null
        var digestQuery: InformationMonitorQuery? = null
        var callCount = 0

        val tool = buildInboxMonitorTool(
            isConfigured = { configured },
            loadStatus = { query ->
                callCount += 1
                statusQuery = query
                failure?.let { throw it }
                InformationMonitorSnapshot(statusEnvelope(), freshness)
            },
            loadItems = { query ->
                callCount += 1
                itemsQuery = query
                failure?.let { throw it }
                InformationMonitorSnapshot(itemsEnvelope(), freshness)
            },
            loadDigest = { query ->
                callCount += 1
                digestQuery = query
                failure?.let { throw it }
                InformationMonitorSnapshot(digestEnvelope(), freshness)
            },
        )
    }

    private companion object {
        val FORBIDDEN_RESPONSE_KEYS = setOf(
            "\"body\"",
            "\"raw\"",
            "\"content\"",
            "\"credentials\"",
            "\"providerItemId\"",
        )

        fun item() = InformationMonitorItem(
            id = "event-1",
            sourceLabel = "工作邮箱",
            channel = InformationMonitorChannel.EMAIL,
            occurredAt = "2026-07-26T12:00:00Z",
            sender = "example@example.com",
            title = "项目更新",
            summary = "项目已进入验收阶段。",
            actionItems = listOf("查看验收结果"),
            importance = InformationMonitorImportance.HIGH,
        )

        fun statusEnvelope() = InformationMonitorStatusEnvelope(
            schema = INFORMATION_MONITOR_SCHEMA,
            sources = listOf(
                InformationMonitorSourceStatus(
                    sourceLabel = "工作邮箱",
                    kind = InformationMonitorChannel.EMAIL,
                    state = InformationMonitorSourceState.OK,
                    lastSucceededAt = "2026-07-26T12:00:00Z",
                    itemCount24h = 1,
                ),
            ),
        )

        fun itemsEnvelope() = InformationMonitorItemsEnvelope(
            schema = INFORMATION_MONITOR_SCHEMA,
            items = listOf(item()),
        )

        fun digestEnvelope() = InformationMonitorDigestEnvelope(
            schema = INFORMATION_MONITOR_SCHEMA,
            total = 1,
            highPriority = 1,
            channels = listOf(
                InformationMonitorDigestChannel(
                    channel = InformationMonitorChannel.EMAIL,
                    count = 1,
                    topItems = listOf(item()),
                ),
            ),
        )
    }
}

private fun List<UIMessagePart>.json(): JsonObject {
    val text = (single() as UIMessagePart.Text).text
    return Json.parseToJsonElement(text).jsonObject
}
