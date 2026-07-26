package me.rerere.rikkahub.data.work

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneWorkSessionCreatorTest {
    @Test
    fun `generated title is sent through the real create request`() = runBlocking {
        val gateway = RecordingGateway()
        val creator = PhoneWorkSessionCreator(
            gateway = gateway,
            titleGenerator = PhoneWorkTitleGenerator { "修复 Work 会话标题" },
        )

        creator.create(REPO, "gpt-5.6-sol", "high", "请修复标题")

        assertEquals("修复 Work 会话标题", gateway.request?.title)
        assertEquals("请修复标题", gateway.request?.message)
        assertEquals("runner-1", gateway.request?.runnerId)
        assertEquals("codex", gateway.request?.runtime)
    }

    @Test
    fun `fast model failure still sends first-message fallback`() = runBlocking {
        val gateway = RecordingGateway()
        val creator = PhoneWorkSessionCreator(
            gateway = gateway,
            titleGenerator = PhoneWorkTitleGenerator { null },
        )

        creator.create(REPO, "gpt-5.6-sol", "high", "# 修复 Work 标题\n更多说明")

        assertEquals("修复 Work 标题", gateway.request?.title)
    }

    @Test
    fun `selected Claude Code runtime is preserved in create request`() = runBlocking {
        val gateway = RecordingGateway()
        val creator = PhoneWorkSessionCreator(
            gateway = gateway,
            titleGenerator = PhoneWorkTitleGenerator { "Claude 会话" },
        )

        creator.create(
            repo = REPO,
            model = "sonnet",
            reasoningEffort = "high",
            message = "继续处理",
            runtime = "claude-code",
        )

        assertEquals("claude-code", gateway.request?.runtime)
        assertEquals("sonnet", gateway.request?.model)
    }

    @Test
    fun `legacy repository catalog exposes Codex runtime`() {
        val runtime = REPO.effectiveRuntimes().single()

        assertEquals("codex", runtime.id)
        assertEquals(REPO.models, runtime.models)
        assertEquals(REPO.reasoningEfforts, runtime.reasoningEfforts)
    }

    private class RecordingGateway : PhoneWorkSessionGateway {
        var request: CreateSessionRequest? = null

        override suspend fun createSession(
            request: CreateSessionRequest,
            imageUrls: List<String>,
        ): PhoneWorkSession {
            this.request = request
            return PhoneWorkSession(
                id = "work-1",
                runnerId = request.runnerId,
                repoId = request.repoId,
                repoName = REPO.name,
                title = request.title,
                runtime = request.runtime,
                model = request.model,
                reasoningEffort = request.reasoningEffort,
                status = "QUEUED",
                createdAt = "2026-07-22T00:00:00Z",
                updatedAt = "2026-07-22T00:00:00Z",
            )
        }
    }

    private companion object {
        val REPO = PhoneWorkRepo(
            id = "repo-1",
            runnerId = "runner-1",
            name = "zhixing",
            models = listOf("gpt-5.6-sol"),
            reasoningEfforts = listOf("high"),
            available = true,
        )
    }
}
