package me.rerere.rikkahub.ui.pages.workflow

import me.rerere.rikkahub.ui.pages.workflow.happy.HappyEncryptionVariant
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyMachine
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkflowProjectTest {
    @Test
    fun `groups all codex history by machine and normalized project path`() {
        val machine = machine("machine-1")
        val sessions = listOf(
            session("new", "C:\\Work\\Zhixing", 300, flavor = "codex"),
            session("old", "c:/work/zhixing/", 100, codexThreadId = "thread-old"),
            session("claude", "C:/work/zhixing", 400, flavor = "claude"),
        )

        val projects = buildWorkflowProjects(sessions, listOf(machine))

        assertEquals(1, projects.size)
        assertEquals("Zhixing", projects.single().name)
        assertEquals(listOf("new", "old"), projects.single().sessions.map { it.id })
        assertFalse(projects.single().isOnline)
    }

    private fun machine(id: String) = HappyMachine(
        id = id,
        host = "devbox",
        displayName = null,
        platform = "win32",
        active = false,
        activeAt = 0,
        supportsCodex = true,
        homeDir = "C:\\Users\\dev",
        encryptionKey = ByteArray(32),
        encryptionVariant = HappyEncryptionVariant.DATA_KEY,
    )

    private fun session(
        id: String,
        path: String,
        updatedAt: Long,
        flavor: String? = null,
        codexThreadId: String? = null,
    ) = HappySession(
        id = id,
        name = null,
        path = path,
        host = "devbox",
        machineId = "machine-1",
        codexThreadId = codexThreadId,
        flavor = flavor,
        active = false,
        activeAt = updatedAt,
        createdAt = updatedAt - 10,
        updatedAt = updatedAt,
        approvals = emptyList(),
        encryptionKey = ByteArray(32),
        encryptionVariant = HappyEncryptionVariant.DATA_KEY,
    )
}
