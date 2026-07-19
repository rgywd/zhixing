package me.rerere.rikkahub.data.workflow.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexCatalogCurationTest {
    @Test
    fun `home excludes hidden and archive-only projects`() {
        val catalog = curateCodexHome(
            listOf(
                project("active", thread("current", archived = false)),
                project("archive", thread("old", archived = true)),
                project("hidden", thread("hidden-current", archived = false), hidden = true),
                project("deleted", thread("orphan", archived = false), existsOnDisk = false),
            )
        )

        assertEquals(listOf("active"), catalog.projects.map(CodexProject::projectId))
    }

    @Test
    fun `pinned archive-only projects stay on home and do not consume history`() {
        val catalog = curateCodexHome(
            listOf(
                project("pinned", thread("old", archived = true), pinned = true),
                project("recent", thread("current", archived = false, recencyAt = 20)),
            ),
            projectLimit = 1,
        )

        assertEquals(listOf("pinned"), catalog.projects.map(CodexProject::projectId))
        assertEquals(1, catalog.remainingProjectCount)
    }

    @Test
    fun `home limits recent projects while management data remains untouched`() {
        val projects = (1..12).map { index ->
            project("project-$index", thread("thread-$index", archived = false, recencyAt = index.toLong()))
        }

        val catalog = curateCodexHome(projects)

        assertEquals(8, catalog.projects.size)
        assertEquals("project-12", catalog.projects.first().projectId)
        assertEquals(4, catalog.remainingProjectCount)
        assertEquals(12, projects.size)
    }

    @Test
    fun `pinned archived threads surface unless their project is explicitly hidden`() {
        val catalog = curateCodexHome(
            listOf(
                project(
                    "history",
                    thread("valuable", archived = true, pinned = true),
                ),
                project("hidden", thread("hidden-value", archived = true, pinned = true), hidden = true),
            )
        )

        assertTrue(catalog.projects.isEmpty())
        assertEquals(listOf("valuable"), catalog.pinnedThreads.map(CodexThread::threadId))
        assertTrue(catalog.pinnedThreads.single().archived)
        assertFalse(catalog.pinnedThreads.single().isSubagent)
    }

    private fun project(
        id: String,
        vararg threads: CodexThread,
        pinned: Boolean = false,
        hidden: Boolean = false,
        existsOnDisk: Boolean = true,
    ) = CodexProject(
        projectId = id,
        machineId = "machine",
        displayName = id,
        canonicalRoot = "C:/$id",
        existsOnDisk = existsOnDisk,
        vcsKind = "git",
        branch = "main",
        updatedAt = threads.maxOfOrNull(CodexThread::recencyAt) ?: 0,
        isPinned = pinned,
        isHidden = hidden,
        machine = null,
        threads = threads.toList(),
    )

    private fun thread(
        id: String,
        archived: Boolean,
        pinned: Boolean = false,
        recencyAt: Long = 1,
    ) = CodexThread(
        machineId = "machine",
        threadId = id,
        projectId = "project",
        name = id,
        preview = "",
        createdAt = recencyAt,
        updatedAt = recencyAt,
        recencyAt = recencyAt,
        archived = archived,
        source = "codex",
        parentThreadId = null,
        forkedFromId = null,
        isSubagent = false,
        isAutomation = false,
        runtimeState = CodexRuntimeState.IDLE,
        rawStatus = "idle",
        isPinned = pinned,
    )
}
