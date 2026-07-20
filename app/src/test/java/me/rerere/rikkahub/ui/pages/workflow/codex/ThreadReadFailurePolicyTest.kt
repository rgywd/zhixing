package me.rerere.rikkahub.ui.pages.workflow.codex

import me.rerere.rikkahub.data.work.AppServerRpcException
import me.rerere.rikkahub.data.workflow.codex.CodexAttachment
import me.rerere.rikkahub.data.workflow.codex.CodexThreadDetail
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadReadFailurePolicyTest {
    @Test
    fun `successful first turn persists even when the transient marker was cleared`() {
        assertEquals(
            true,
            directThreadNeedsMaterialization(
                pendingThreadId = null,
                storedThreadId = null,
                targetThreadId = "thread-new",
            ),
        )
        assertEquals(
            false,
            directThreadNeedsMaterialization(
                pendingThreadId = null,
                storedThreadId = "thread-existing",
                targetThreadId = "thread-existing",
            ),
        )
    }

    @Test
    fun `authoritative snapshot keeps phone attachment mappings`() {
        val attachment = CodexAttachment(
            remotePath = "C:/uploads/spec.md",
            localUri = "content://phone/spec.md",
            fileName = "spec.md",
            mime = "text/markdown",
        )
        val merged = preserveDirectLocalMetadata(
            snapshot = CodexThreadDetail(null, emptyList()),
            previous = CodexThreadDetail(
                null,
                emptyList(),
                attachments = mapOf(attachment.remotePath to attachment),
            ),
        )

        assertEquals(attachment, merged.attachments.getValue(attachment.remotePath))
    }

    @Test
    fun `empty thread is allowed before its first user message`() {
        val error = AppServerRpcException(
            -32600,
            "thread thread_1 is not materialized yet; includeTurns is unavailable before first user message",
        )

        assertEquals(
            ThreadReadFailureDisposition.UNMATERIALIZED,
            classifyThreadReadFailure(error, expectMaterialized = false),
        )
        assertEquals(
            ThreadReadFailureDisposition.RETRY,
            classifyThreadReadFailure(error, expectMaterialized = true),
        )
    }

    @Test
    fun `first turn retries while its rollout is being flushed`() {
        val error = AppServerRpcException(
            -32603,
            "failed to load thread history: rollout at C:/sessions/rollout.jsonl is empty",
        )

        assertEquals(
            ThreadReadFailureDisposition.RETRY,
            classifyThreadReadFailure(error, expectMaterialized = true),
        )
    }

    @Test
    fun `unrelated thread read errors remain fatal`() {
        assertEquals(
            ThreadReadFailureDisposition.FATAL,
            classifyThreadReadFailure(
                AppServerRpcException(-32603, "thread-store internal error: permission denied"),
                expectMaterialized = true,
            ),
        )
    }
}
