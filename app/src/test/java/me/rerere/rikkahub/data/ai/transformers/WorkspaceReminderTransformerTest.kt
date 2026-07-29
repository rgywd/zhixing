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
            vaultReady = true,
        )

        assertFalse(prompt.contains("knowledge_search"))
        assertFalse(prompt.contains("PROJECT.md"))
        assertFalse(prompt.contains("workspace_shell"))
        assertFalse(prompt.contains("Linux Rootfs is ready"))
        assertTrue(prompt.contains("vault/AGENTS.md"))
        assertTrue(prompt.contains("00_收件箱"))
        assertTrue(prompt.contains("30_研究"))
        assertTrue(prompt.contains("type: reference"))
        assertTrue(prompt.contains("60_工具"))
        assertTrue(prompt.contains("type: tool"))
    }

    @Test
    fun shellInstructionsOnlyAppearWhenRootfsIsReadyAndUseVaultPath() {
        val prompt = buildWorkspacePrompt(
            workspace = workspace(WorkspaceShellStatus.READY),
            cwd = "/workspace/vault/60_工具",
            vaultReady = true,
        )

        assertTrue(prompt.contains("workspace_shell"))
        assertTrue(prompt.contains("/workspace/vault"))
        assertTrue(prompt.contains("/workspace/vault/60_工具"))
        assertTrue(prompt.contains("/workspace/vault/.agents/skills"))
        assertFalse(prompt.contains("knowledge_status"))
        assertFalse(prompt.contains("not initialized as a knowledge space"))
    }

    @Test
    fun ordinaryWorkspaceDoesNotReceiveVaultInstructions() {
        val prompt = buildWorkspacePrompt(
            workspace = workspace(WorkspaceShellStatus.DISABLED),
            vaultReady = false,
        )

        assertFalse(prompt.contains("vault/AGENTS.md"))
        assertFalse(prompt.contains("00_收件箱"))
        assertFalse(prompt.contains("type: tool"))
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
