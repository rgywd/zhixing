package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceReminderTransformerTest {
    @Test
    fun initializedKnowledgeSpaceIsNotPromotedAsARequiredPreflight() {
        val prompt = buildWorkspacePrompt(
            workspace = workspace(WorkspaceShellStatus.DISABLED),
        )

        assertFalse(prompt.contains("knowledge_search"))
        assertFalse(prompt.contains("PROJECT.md"))
        assertFalse(prompt.contains("workspace_shell"))
        assertFalse(prompt.contains("Linux Rootfs is ready"))
    }

    @Test
    fun shellInstructionsOnlyAppearWhenRootfsIsReady() {
        val prompt = buildWorkspacePrompt(
            workspace = workspace(WorkspaceShellStatus.READY),
            cwd = "/workspace/notes",
        )

        assertTrue(prompt.contains("workspace_shell"))
        assertTrue(prompt.contains("/workspace/notes"))
        assertFalse(prompt.contains("knowledge_status"))
        assertFalse(prompt.contains("not initialized as a knowledge space"))
    }

    private fun workspace(status: WorkspaceShellStatus) = WorkspaceEntity(
        id = "workspace-id",
        name = "项目",
        root = "workspace-id",
        shellStatus = status.name,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
