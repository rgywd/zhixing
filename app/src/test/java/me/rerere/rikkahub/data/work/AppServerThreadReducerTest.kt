package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServerThreadReducerTest {
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
}
