package me.rerere.rikkahub.ui.pages.work

import me.rerere.rikkahub.data.work.PhoneWorkRepo
import me.rerere.rikkahub.data.work.PhoneWorkRepoKey
import me.rerere.rikkahub.data.work.PhoneWorkRepoPreferences
import me.rerere.rikkahub.data.work.PhoneWorkQueueItem
import me.rerere.rikkahub.data.work.PhoneWorkRunnerCapabilities
import me.rerere.rikkahub.data.work.PhoneWorkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWorkChatInteractionTest {
    @Test
    fun `repository search keeps available matches grouped by discovery root`() {
        val repos = listOf(
            repo(id = "company", name = "backend", group = "Workspace"),
            repo(id = "personal", name = "notes", group = "Documents"),
            repo(id = "deleted", name = "backend-old", group = "Workspace", available = false),
        )

        val result = buildWorkRepoSections(repos, "work", PhoneWorkRepoPreferences())

        assertEquals(listOf("Workspace"), result.map { it.title })
        assertEquals(listOf("company"), result.single().repos.map { it.id })
    }

    @Test
    fun `repository sections put pinned and recent choices before remaining groups without duplicates`() {
        val repos = listOf(
            repo(id = "alpha", name = "alpha", group = "Workspace"),
            repo(id = "beta", name = "beta", group = "Workspace"),
            repo(id = "notes", name = "notes", group = "Documents"),
            repo(id = "tooling", name = "tooling", group = "Workspace"),
        )
        val preferences = PhoneWorkRepoPreferences(
            pinned = listOf(key("tooling")),
            recent = listOf(key("beta"), key("tooling")),
        )

        val result = buildWorkRepoSections(repos, "", preferences)

        assertEquals(listOf("置顶", "最近使用", "Documents", "Workspace"), result.map { it.title })
        assertEquals(listOf("tooling"), result[0].repos.map { it.id })
        assertEquals(listOf("beta"), result[1].repos.map { it.id })
        assertEquals(listOf("notes"), result[2].repos.map { it.id })
        assertEquals(listOf("alpha"), result[3].repos.map { it.id })
    }

    @Test
    fun `repository sections preserve preference order and hide unavailable entries`() {
        val repos = listOf(
            repo(id = "first", name = "first", group = "Workspace"),
            repo(id = "second", name = "second", group = "Workspace"),
            repo(id = "gone", name = "gone", group = "Workspace", available = false),
        )
        val preferences = PhoneWorkRepoPreferences(
            pinned = listOf(key("second"), key("gone"), key("first")),
        )

        val result = buildWorkRepoSections(repos, "", preferences)

        assertEquals(listOf("second", "first"), result.single().repos.map { it.id })
    }

    @Test
    fun `quote prefixes every line and leaves cursor spacing`() {
        assertEquals("> first\n> second\n\n", mergeWorkQuote("", "first\nsecond"))
        assertEquals("draft\n\n> reply\n\n", mergeWorkQuote("draft  ", "reply"))
    }

    @Test
    fun `new items follow when user was at bottom`() {
        val decision = decideWorkScroll(previousItemCount = 5, newItemCount = 7, lastVisibleIndex = 4)

        assertTrue(decision.followLatest)
        assertEquals(0, decision.addedItems)
    }

    @Test
    fun `new items accumulate when user is reading history`() {
        val decision = decideWorkScroll(previousItemCount = 5, newItemCount = 7, lastVisibleIndex = 2)

        assertFalse(decision.followLatest)
        assertEquals(2, decision.addedItems)
    }

    @Test
    fun `removed status rows do not create unread messages`() {
        val decision = decideWorkScroll(previousItemCount = 7, newItemCount = 6, lastVisibleIndex = 2)

        assertFalse(decision.followLatest)
        assertEquals(0, decision.addedItems)
    }

    @Test
    fun `tap queues while a capable runner is running`() {
        assertEquals(
            WorkInputAction.QUEUE,
            resolveWorkInputAction(
                session = session(status = "RUNNING", activeTurnId = "turn-1"),
                capabilities = PhoneWorkRunnerCapabilities(editableQueue = true),
                longPress = false,
            ),
        )
    }

    @Test
    fun `long press steers only a live supported turn`() {
        val capabilities = PhoneWorkRunnerCapabilities(appServerTurns = true, steer = true, editableQueue = true)

        assertEquals(
            WorkInputAction.STEER,
            resolveWorkInputAction(session("RUNNING", "turn-1"), capabilities, longPress = true),
        )
        assertEquals(
            WorkInputAction.STEER_UNAVAILABLE,
            resolveWorkInputAction(session("RUNNING", null), capabilities, longPress = true),
        )
        assertEquals(
            WorkInputAction.STEER_UNAVAILABLE,
            resolveWorkInputAction(session("WAITING_FOR_USER", "turn-1"), capabilities, longPress = true),
        )
    }

    @Test
    fun `idle and legacy runners preserve direct send`() {
        assertEquals(
            WorkInputAction.DIRECT,
            resolveWorkInputAction(
                session("IDLE", null),
                PhoneWorkRunnerCapabilities(editableQueue = true),
                longPress = false,
            ),
        )
        assertEquals(
            WorkInputAction.DIRECT,
            resolveWorkInputAction(
                session("RUNNING", "turn-1"),
                PhoneWorkRunnerCapabilities(),
                longPress = false,
            ),
        )
    }

    @Test
    fun `queue preview shows first text line and attachment fallback`() {
        assertEquals("first", workQueueItemPreview(queueItem(text = " first\nsecond ")))
        assertEquals("1 个附件", workQueueItemPreview(queueItem(text = "", attachments = 1)))
    }

    private fun repo(
        id: String,
        name: String,
        group: String,
        available: Boolean = true,
    ) = PhoneWorkRepo(
        id = id,
        runnerId = "runner",
        name = name,
        models = listOf("gpt-5.6-sol"),
        reasoningEfforts = listOf("high"),
        available = available,
        group = group,
    )

    private fun key(repoId: String) = PhoneWorkRepoKey(runnerId = "runner", repoId = repoId)

    private fun session(status: String, activeTurnId: String?) = PhoneWorkSession(
        id = "session",
        runnerId = "runner",
        repoId = "repo",
        repoName = "repo",
        model = "gpt-5.6-sol",
        reasoningEffort = "high",
        status = status,
        activeTurnId = activeTurnId,
        createdAt = "2026-08-14T00:00:00Z",
        updatedAt = "2026-08-14T00:00:00Z",
    )

    private fun queueItem(text: String, attachments: Int = 0) = PhoneWorkQueueItem(
        id = "queue",
        sessionId = "session",
        text = text,
        attachments = List(attachments) {
            me.rerere.rikkahub.data.work.PhoneWorkAttachment(
                id = "attachment-$it",
                fileName = "file-$it.txt",
                mimeType = "text/plain",
                size = 1,
                sha256 = "sha",
            )
        },
        state = "QUEUED",
        revision = 1,
        createdAt = "2026-08-14T00:00:00Z",
        updatedAt = "2026-08-14T00:00:00Z",
    )
}
