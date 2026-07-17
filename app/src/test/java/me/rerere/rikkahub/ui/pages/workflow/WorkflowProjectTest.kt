package me.rerere.rikkahub.ui.pages.workflow

import me.rerere.rikkahub.data.workflow.WorkAgent
import me.rerere.rikkahub.data.workflow.WorkMachine
import me.rerere.rikkahub.data.workflow.WorkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkflowProjectTest {
    @Test
    fun `groups codex and claude history by machine and normalized project path`() {
        val machine = machine("machine-1")
        val sessions = listOf(
            session("new", "C:\\Work\\Zhixing", 300, agent = WorkAgent.CODEX),
            session("old", "c:/work/zhixing/", 100, agent = WorkAgent.CODEX),
            session("claude", "C:/Work/Zhixing", 400, agent = WorkAgent.CLAUDE),
        )

        val projects = buildWorkflowProjects(sessions, listOf(machine))

        assertEquals(1, projects.size)
        assertEquals("Zhixing", projects.single().name)
        assertEquals(listOf("claude", "new", "old"), projects.single().sessions.map { it.id })
        assertFalse(projects.single().isOnline)
    }

    @Test
    fun `sessions without machine or path are dropped`() {
        val sessions = listOf(
            session("ok", "/repo", 100, agent = WorkAgent.CLAUDE),
            session("no-path", "", 200, agent = WorkAgent.CODEX),
        )

        val projects = buildWorkflowProjects(sessions, emptyList())

        assertEquals(1, projects.size)
        assertEquals(listOf("ok"), projects.single().sessions.map { it.id })
    }

    private fun machine(id: String) = WorkMachine(
        id = id,
        host = "devbox",
        displayName = null,
        platform = "win32",
        active = false,
        activeAt = 0,
        supportsCodex = true,
        supportsClaude = true,
        homeDir = "C:\\Users\\dev",
    )

    private fun session(
        id: String,
        path: String,
        updatedAt: Long,
        agent: WorkAgent,
    ) = WorkSession(
        id = id,
        machineId = "machine-1",
        path = path,
        host = "devbox",
        name = null,
        agent = agent,
        active = false,
        activeAt = updatedAt,
        createdAt = updatedAt - 10,
        updatedAt = updatedAt,
        approvals = emptyList(),
        decryptable = true,
    )
}
