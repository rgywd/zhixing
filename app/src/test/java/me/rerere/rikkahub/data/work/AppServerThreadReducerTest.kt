package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.workflow.codex.CodexThreadDetail
import me.rerere.rikkahub.data.workflow.codex.CodexTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServerThreadReducerTest {
    @Test
    fun `active turn id restores the latest running turn after process restart`() {
        val detail = CodexThreadDetail(
            thread = null,
            turns = listOf(
                CodexTurn("done", "completed", null, null, null, emptyList()),
                CodexTurn("running", "inProgress", null, null, null, emptyList()),
            ),
        )

        assertEquals("running", AppServerThreadReducer.activeTurnId(detail))
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `history and deltas use one projector model`() {
        val detail = AppServerThreadReducer.snapshot(
            json.parseToJsonElement(
                """{"thread":{"id":"thread-1","preview":"Fix UI","createdAt":1,"updatedAt":2,"recencyAt":2,"status":{"type":"active"},"cwd":"C:/src/zhixing","turns":[{"id":"turn-1","status":"inProgress","startedAt":2,"completedAt":null,"error":null,"items":[{"type":"userMessage","id":"user-1","clientId":null,"content":[{"type":"text","text":"Fix UI","text_elements":[]}]}]}]}}"""
            ),
            repositoryId = "repo-1",
        )

        val updated = AppServerThreadReducer.apply(
            detail,
            AppServerNotification(
                "item/agentMessage/delta",
                json.parseToJsonElement("""{"threadId":"thread-1","turnId":"turn-1","itemId":"agent-1","delta":"Done"}"""),
            ),
        )

        assertEquals("thread-1", updated.thread?.threadId)
        assertEquals("Fix UI", updated.turns.single().items.first().text)
        assertEquals("Done", updated.turns.single().items.last().text)
        assertTrue(updated.thread?.runtimeState?.name == "RUNNING")
    }

    @Test
    fun `sparse turn completed notification keeps streamed items`() {
        val detail = AppServerThreadReducer.snapshot(
            json.parseToJsonElement(
                """{"thread":{"id":"thread-1","preview":"Reply","createdAt":1,"updatedAt":2,"status":{"type":"active"},"turns":[{"id":"turn-1","status":"inProgress","items":[{"type":"userMessage","id":"user-1","content":[{"type":"text","text":"Hello","text_elements":[]}]},{"type":"agentMessage","id":"agent-1","text":"World","status":"completed"}]}]}}"""
            ),
            repositoryId = "repo-1",
        )

        val completed = AppServerThreadReducer.apply(
            detail,
            AppServerNotification(
                "turn/completed",
                json.parseToJsonElement(
                    """{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed","items":[]}}"""
                ),
            ),
        )

        assertEquals("completed", completed.turns.single().status)
        assertEquals(listOf("Hello", "World"), completed.turns.single().items.map { it.text })
    }

    @Test
    fun `attachment manifest never leaks into restored thread title`() {
        val manifest = AppServerAttachmentManifest.append(
            "Read the attached file",
            listOf(AppServerAttachedFile("spec.md", "C:/uploads/spec.md", "text/markdown")),
        )
        val detail = AppServerThreadReducer.snapshot(
            json.parseToJsonElement(
                """{"thread":{"id":"thread-1","name":${JsonPrimitive(manifest)},"preview":${JsonPrimitive(manifest)},"createdAt":1,"updatedAt":2,"status":{"type":"idle"},"turns":[]}}"""
            ),
            repositoryId = "repo-1",
        )

        assertEquals("Read the attached file", detail.thread?.name)
        assertEquals("Read the attached file", detail.thread?.preview)
    }

    @Test
    fun `request user input becomes native ask user tool`() {
        val detail = AppServerThreadReducer.snapshot(
            json.parseToJsonElement(
                """{"thread":{"id":"thread-1","preview":"Ask","createdAt":1,"updatedAt":2,"status":{"type":"active"},"turns":[{"id":"turn-1","status":"inProgress","items":[]}]}}"""
            ),
            repositoryId = "repo-1",
        )

        val updated = AppServerThreadReducer.applyServerRequest(
            detail,
            AppServerRequest(
                JsonPrimitive(7),
                "item/tool/requestUserInput",
                json.parseToJsonElement(
                    """{"threadId":"thread-1","turnId":"turn-1","itemId":"ask-1","questions":[{"id":"scope","header":"范围","question":"选哪个？","isOther":false,"isSecret":false,"options":[{"label":"当前页","description":"small"}]}]}"""
                ),
            ),
        )

        val item = updated.turns.single().items.single()
        assertEquals("dynamicToolCall", item.rawType)
        assertTrue(item.raw.toString().contains("ask_user"))
        assertTrue(item.raw.toString().contains("当前页"))
    }

    @Test
    fun `request user input creates a missing turn and item projection`() {
        val detail = AppServerThreadReducer.snapshot(
            json.parseToJsonElement(
                """{"thread":{"id":"thread-1","preview":"Ask","createdAt":1,"updatedAt":2,"status":{"type":"active"},"turns":[]}}"""
            ),
            repositoryId = "repo-1",
        )

        val updated = AppServerThreadReducer.applyServerRequest(
            detail,
            AppServerRequest(
                JsonPrimitive(8),
                "item/tool/requestUserInput",
                json.parseToJsonElement(
                    """{"threadId":"thread-1","turnId":"turn-late","itemId":"ask-late","questions":[{"id":"scope","question":"范围？","options":[]}]}"""
                ),
            ),
        )

        assertTrue(AppServerThreadReducer.containsItem(updated, "ask-late"))
        assertEquals("turn-late", updated.turns.single().turnId)
    }

    @Test
    fun `official streaming notifications update the canonical item model`() {
        var detail = AppServerThreadReducer.snapshot(
            json.parseToJsonElement(
                """{"thread":{"id":"thread-1","preview":"Run","createdAt":1,"updatedAt":2,"status":{"type":"active"},"turns":[{"id":"turn-1","status":"inProgress","items":[]}]}}"""
            ),
            repositoryId = "repo-1",
        )
        fun apply(method: String, params: String) {
            detail = AppServerThreadReducer.apply(
                detail,
                AppServerNotification(method, json.parseToJsonElement(params)),
            )
        }

        apply("item/plan/delta", """{"threadId":"thread-1","turnId":"turn-1","itemId":"plan-1","delta":"Step one"}""")
        apply("item/commandExecution/outputDelta", """{"threadId":"thread-1","turnId":"turn-1","itemId":"cmd-1","delta":"stdout"}""")
        apply("item/fileChange/patchUpdated", """{"threadId":"thread-1","turnId":"turn-1","itemId":"patch-1","changes":[{"path":"a.kt","kind":{"type":"add"},"diff":"+x"}]}""")
        apply("item/mcpToolCall/progress", """{"threadId":"thread-1","turnId":"turn-1","itemId":"mcp-1","message":"working"}""")
        apply("turn/plan/updated", """{"threadId":"thread-1","turnId":"turn-1","explanation":"why","plan":[{"step":"test","status":"inProgress"}]}""")
        apply("model/rerouted", """{"threadId":"thread-1","turnId":"turn-1","fromModel":"a","toModel":"b","reason":"fallback"}""")
        apply("future/threadEvent", """{"threadId":"thread-1","turnId":"turn-1","value":"opaque"}""")

        val items = detail.turns.single().items.associateBy { it.itemId }
        assertEquals("Step one", items.getValue("plan-1").text)
        assertTrue(items.getValue("cmd-1").raw.toString().contains("stdout"))
        assertTrue(items.getValue("patch-1").raw.toString().contains("a.kt"))
        assertTrue(items.getValue("mcp-1").raw.toString().contains("working"))
        assertTrue(items.containsKey("turn-plan-turn-1"))
        assertTrue(items.values.any { it.rawType == "modelRerouted" && it.text?.contains("b") == true })
        assertTrue(items.values.any { it.rawType == "opaqueNotification" })
    }

    @Test
    fun `error notification is visible and moves thread to system error`() {
        val detail = AppServerThreadReducer.snapshot(
            json.parseToJsonElement(
                """{"thread":{"id":"thread-1","preview":"Run","createdAt":1,"updatedAt":2,"status":{"type":"active"},"turns":[{"id":"turn-1","status":"inProgress","items":[]}]}}"""
            ),
            repositoryId = "repo-1",
        )
        val failed = AppServerThreadReducer.apply(
            detail,
            AppServerNotification(
                "error",
                json.parseToJsonElement(
                    """{"threadId":"thread-1","turnId":"turn-1","willRetry":false,"error":{"message":"boom","codexErrorInfo":null,"additionalDetails":null}}"""
                ),
            ),
        )

        assertEquals("SYSTEM_ERROR", failed.thread?.runtimeState?.name)
        assertEquals("boom", failed.turns.single().error)
        assertTrue(failed.turns.single().items.any { it.text == "boom" })
    }
}
