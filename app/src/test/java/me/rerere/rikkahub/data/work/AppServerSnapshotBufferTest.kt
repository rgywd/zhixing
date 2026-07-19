package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.buildJsonObject
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

        val restored = buffer.complete(snapshot("old"))

        assertEquals("oldnew", restored.turns.single().items.single().text)
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
