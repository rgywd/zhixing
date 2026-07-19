package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.workflow.codex.CodexItem
import me.rerere.rikkahub.data.workflow.codex.CodexRuntimeState
import me.rerere.rikkahub.data.workflow.codex.CodexThread
import me.rerere.rikkahub.data.workflow.codex.CodexThreadDetail
import me.rerere.rikkahub.data.workflow.codex.CodexTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServerSnapshotBufferTest {
    @Test
    fun `notification newer than in-flight snapshot is replayed after snapshot`() {
        val buffer = AppServerSnapshotBuffer()
        buffer.begin("thread-1")

        assertTrue(buffer.offer(AppServerNotification(
            method = "item/agentMessage/delta",
            params = buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "item-1")
                put("delta", "new")
            },
        )))

        val restored = buffer.complete(snapshot("old")).detail

        assertEquals("oldnew", restored.turns.single().items.single().text)
    }

    @Test
    fun `server request newer than snapshot is replayed after snapshot`() {
        val buffer = AppServerSnapshotBuffer()
        buffer.begin("thread-1")
        val request = AppServerRequest(
            id = JsonPrimitive(7),
            method = "item/commandExecution/requestApproval",
            params = buildJsonObject {
                put("threadId", "thread-1")
                put("itemId", "item-approval")
            },
        )

        assertTrue(buffer.offer(request))

        assertEquals(listOf(request), buffer.complete(snapshot("old")).serverRequests)
    }

    @Test
    fun `abort preserves both newer notifications and server requests`() {
        val buffer = AppServerSnapshotBuffer()
        buffer.begin("thread-1")
        buffer.offer(AppServerNotification(
            method = "item/agentMessage/delta",
            params = buildJsonObject {
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                put("itemId", "item-1")
                put("delta", "new")
            },
        ))
        val request = AppServerRequest(
            id = JsonPrimitive(8),
            method = "item/tool/requestUserInput",
            params = buildJsonObject { put("threadId", "thread-1") },
        )
        buffer.offer(request)

        val replay = buffer.abort(snapshot("old"))

        assertEquals("oldnew", replay.detail.turns.single().items.single().text)
        assertEquals(listOf(request), replay.serverRequests)
    }

    @Test
    fun `notification for another thread is never consumed`() {
        val buffer = AppServerSnapshotBuffer()
        buffer.begin("thread-1")

        assertFalse(buffer.offer(AppServerNotification(
            method = "thread/status/changed",
            params = buildJsonObject {
                put("threadId", "thread-2")
                put("status", "active")
            },
        )))
    }

    private fun snapshot(text: String) = CodexThreadDetail(
        thread = CodexThread(
            machineId = "direct:connection-1",
            threadId = "thread-1",
            projectId = "repo-1",
            name = "Thread",
            preview = text,
            createdAt = 1L,
            updatedAt = 2L,
            recencyAt = 2L,
            archived = false,
            source = "appServer",
            parentThreadId = null,
            forkedFromId = null,
            isSubagent = false,
            isAutomation = false,
            runtimeState = CodexRuntimeState.RUNNING,
            rawStatus = "active",
            isPinned = false,
        ),
        turns = listOf(CodexTurn(
            turnId = "turn-1",
            status = "inProgress",
            startedAt = 1L,
            completedAt = null,
            error = null,
            items = listOf(CodexItem(
                itemId = "item-1",
                type = "agentMessage",
                rawType = "agentMessage",
                role = "agent",
                text = text,
                status = "inProgress",
            )),
        )),
    )
}
