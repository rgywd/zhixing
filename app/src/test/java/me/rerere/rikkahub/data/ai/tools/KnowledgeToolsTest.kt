package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeToolsTest {
    @Test
    fun `legacy knowledge approval defaults remain readable`() {
        assertFalse(resolveWorkspaceToolApproval("knowledge_status", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("knowledge_search", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("knowledge_read", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("knowledge_ingest", emptyMap()))
    }

    @Test
    fun `legacy workspace override remains readable`() {
        assertFalse(
            resolveWorkspaceToolApproval(
                "knowledge_ingest",
                mapOf("knowledge_ingest" to false),
            )
        )
    }
}
