package me.rerere.rikkahub.ui.pages.work

import me.rerere.rikkahub.data.work.PhoneWorkCatalog
import me.rerere.rikkahub.data.work.PhoneWorkRepo
import me.rerere.rikkahub.data.work.PhoneWorkRuntime
import me.rerere.rikkahub.data.work.PhoneWorkSession
import me.rerere.rikkahub.data.work.effectiveReasoningEfforts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PhoneWorkModelOptionsTest {
    @Test
    fun `new sessions prefer xhigh for Codex and max for Claude Code`() {
        assertEquals(
            "xhigh",
            PhoneWorkSessionVM.preferredEffort("codex", listOf("low", "high", "xhigh", "max")),
        )
        assertEquals(
            "max",
            PhoneWorkSessionVM.preferredEffort("claude-code", listOf("medium", "high", "xhigh", "max")),
        )
    }

    @Test
    fun `default Codex catalog exposes four current models without ultra`() {
        assertEquals(
            listOf(
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "gpt-5.3-codex-spark",
            ),
            PhoneWorkSessionVM.DEFAULT_MODELS,
        )
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            PhoneWorkSessionVM.DEFAULT_EFFORTS,
        )
        assertFalse(PhoneWorkSessionVM.DEFAULT_EFFORTS.contains("ultra"))
    }

    @Test
    fun `Spark uses its model-specific reasoning effort subset`() {
        val runtime = PhoneWorkRuntime(
            id = "codex",
            name = "Codex",
            models = PhoneWorkSessionVM.DEFAULT_MODELS,
            reasoningEfforts = PhoneWorkSessionVM.DEFAULT_EFFORTS,
            reasoningEffortsByModel = PhoneWorkSessionVM.DEFAULT_REASONING_EFFORTS_BY_MODEL,
            fastModels = listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"),
        )

        assertEquals(
            listOf("low", "medium", "high", "xhigh"),
            runtime.effectiveReasoningEfforts(PhoneWorkSessionVM.SPARK_MODEL),
        )
        assertEquals(
            PhoneWorkSessionVM.DEFAULT_EFFORTS,
            runtime.effectiveReasoningEfforts("gpt-5.6-luna"),
        )
        assertFalse(PhoneWorkSessionVM.SPARK_MODEL in runtime.fastModels)
    }

    @Test
    fun `existing session resolves all current reasoning efforts from the live catalog`() {
        val runtime = PhoneWorkRuntime(
            id = "codex",
            name = "Codex",
            models = listOf("gpt-5.6-sol"),
            reasoningEfforts = listOf("low", "medium", "high", "xhigh", "max"),
        )
        val catalog = PhoneWorkCatalog(
            runners = emptyList(),
            repos = listOf(
                PhoneWorkRepo(
                    id = "repo-1",
                    runnerId = "runner-1",
                    name = "zhixing",
                    models = runtime.models,
                    reasoningEfforts = runtime.reasoningEfforts,
                    available = true,
                    runtimes = listOf(runtime),
                ),
            ),
            refreshedAtMillis = 0,
        )
        val session = PhoneWorkSession(
            id = "work-1",
            runnerId = "runner-1",
            repoId = "repo-1",
            repoName = "zhixing",
            runtime = "codex",
            model = "gpt-5.6-sol",
            reasoningEffort = "low",
            status = "IDLE",
            createdAt = "2026-08-12T00:00:00Z",
            updatedAt = "2026-08-12T00:00:00Z",
        )

        assertEquals(runtime.reasoningEfforts, workSessionReasoningEfforts(catalog, session))
    }

    @Test
    fun `existing session falls back to its snapshot when the catalog is unavailable`() {
        val session = PhoneWorkSession(
            id = "work-1",
            runnerId = "runner-1",
            repoId = "repo-1",
            repoName = "zhixing",
            model = "gpt-5.6-sol",
            reasoningEffort = "high",
            status = "IDLE",
            createdAt = "2026-08-12T00:00:00Z",
            updatedAt = "2026-08-12T00:00:00Z",
        )

        assertEquals(listOf("high"), workSessionReasoningEfforts(PhoneWorkCatalog(), session))
    }

    @Test
    fun `existing session does not offer stale efforts while its repository is unavailable`() {
        val session = PhoneWorkSession(
            id = "work-1",
            runnerId = "runner-1",
            repoId = "repo-1",
            repoName = "zhixing",
            model = "gpt-5.6-sol",
            reasoningEffort = "high",
            status = "IDLE",
            createdAt = "2026-08-12T00:00:00Z",
            updatedAt = "2026-08-12T00:00:00Z",
        )
        val catalog = PhoneWorkCatalog(
            repos = listOf(
                PhoneWorkRepo(
                    id = session.repoId,
                    runnerId = session.runnerId,
                    name = session.repoName,
                    models = listOf(session.model),
                    reasoningEfforts = listOf("high", "xhigh"),
                    available = false,
                ),
            ),
        )

        assertEquals(listOf("high"), workSessionReasoningEfforts(catalog, session))
    }

    @Test
    fun `existing session does not offer efforts when its model left the runtime catalog`() {
        val session = PhoneWorkSession(
            id = "work-1",
            runnerId = "runner-1",
            repoId = "repo-1",
            repoName = "zhixing",
            model = "gpt-5.6-sol",
            reasoningEffort = "high",
            status = "IDLE",
            createdAt = "2026-08-12T00:00:00Z",
            updatedAt = "2026-08-12T00:00:00Z",
        )
        val catalog = PhoneWorkCatalog(
            repos = listOf(
                PhoneWorkRepo(
                    id = session.repoId,
                    runnerId = session.runnerId,
                    name = session.repoName,
                    models = listOf("gpt-5.6-luna"),
                    reasoningEfforts = listOf("high", "xhigh"),
                    available = true,
                ),
            ),
        )

        assertEquals(listOf("high"), workSessionReasoningEfforts(catalog, session))
    }

    @Test
    fun `background refresh does not overwrite an effort waiting to be sent`() {
        assertEquals(
            WorkEffortSelection(effort = "xhigh", pending = true),
            reconcileWorkEffortSelection(
                selectedEffort = "xhigh",
                serverEffort = "high",
                pending = true,
            ),
        )
        assertEquals(
            WorkEffortSelection(effort = "xhigh", pending = false),
            reconcileWorkEffortSelection(
                selectedEffort = "xhigh",
                serverEffort = "xhigh",
                pending = true,
            ),
        )
    }

    @Test
    fun `background refresh does not overwrite a Fast change waiting to be sent`() {
        assertEquals(
            WorkFastSelection(fastMode = true, pending = true),
            reconcileWorkFastSelection(
                selectedFastMode = true,
                serverFastMode = false,
                pending = true,
            ),
        )
        assertEquals(
            WorkFastSelection(fastMode = true, pending = false),
            reconcileWorkFastSelection(
                selectedFastMode = true,
                serverFastMode = true,
                pending = true,
            ),
        )
    }
}
