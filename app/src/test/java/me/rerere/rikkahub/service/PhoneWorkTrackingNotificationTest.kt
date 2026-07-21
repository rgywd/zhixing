package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.work.PhoneWorkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneWorkTrackingNotificationTest {
    @Test
    fun `fresh completion replaces aggregate for sixty seconds`() {
        val active = listOf(session("one"), session("two"), session("three"))
        val milestone = WorkTrackingMilestone(
            sessionId = "completed",
            repoName = "zhixing",
            status = WorkTrackingMilestoneStatus.IDLE,
            observedAtMillis = 1_000L,
        )

        val content = buildWorkTrackingNotificationContent(active, milestone, nowMillis = 60_999L)

        assertEquals("zhixing · 本轮任务已完成，仍有 3 个任务正在跟踪", content.text)
        assertEquals("zhixing · 本轮任务已完成\n仍有 3 个任务正在跟踪", content.expandedText)
        assertEquals("completed", content.targetSessionId)
    }

    @Test
    fun `expired completion returns to active aggregate`() {
        val active = listOf(session("one", "alpha"), session("two", "beta"))
        val milestone = WorkTrackingMilestone(
            sessionId = "completed",
            repoName = "zhixing",
            status = WorkTrackingMilestoneStatus.IDLE,
            observedAtMillis = 1_000L,
        )

        val content = buildWorkTrackingNotificationContent(active, milestone, nowMillis = 61_000L)

        assertEquals("alpha 等 2 个任务正在跟踪", content.text)
        assertNull(content.expandedText)
        assertEquals("one", content.targetSessionId)
    }

    @Test
    fun `failure is mirrored while another task remains active`() {
        val milestone = WorkTrackingMilestone(
            sessionId = "failed",
            repoName = "backend",
            status = WorkTrackingMilestoneStatus.FAILED,
            observedAtMillis = 10_000L,
        )

        val content = buildWorkTrackingNotificationContent(
            active = listOf(session("remaining", "frontend")),
            milestone = milestone,
            nowMillis = 20_000L,
        )

        assertEquals("backend · 任务遇到问题，仍有 1 个任务正在跟踪", content.text)
        assertEquals("failed", content.targetSessionId)
    }

    @Test
    fun `explicitly completed conversation uses ended wording`() {
        val milestone = WorkTrackingMilestone(
            sessionId = "completed",
            repoName = "backend",
            status = WorkTrackingMilestoneStatus.COMPLETED,
            observedAtMillis = 10_000L,
        )

        val content = buildWorkTrackingNotificationContent(
            active = listOf(session("remaining", "frontend")),
            milestone = milestone,
            nowMillis = 20_000L,
        )

        assertEquals("backend · 会话已结束，仍有 1 个任务正在跟踪", content.text)
    }

    @Test
    fun `resumed session replaces its stale completion`() {
        val milestone = WorkTrackingMilestone(
            sessionId = "resumed",
            repoName = "zhixing",
            status = WorkTrackingMilestoneStatus.IDLE,
            observedAtMillis = 1_000L,
        )

        val content = buildWorkTrackingNotificationContent(
            active = listOf(session("resumed", "zhixing")),
            milestone = milestone,
            nowMillis = 2_000L,
        )

        assertEquals("zhixing · 进行中", content.text)
        assertNull(content.expandedText)
        assertEquals("resumed", content.targetSessionId)
    }

    @Test
    fun `terminal milestone is ignored when no task remains active`() {
        val milestone = WorkTrackingMilestone(
            sessionId = "completed",
            repoName = "zhixing",
            status = WorkTrackingMilestoneStatus.COMPLETED,
            observedAtMillis = 1_000L,
        )

        val content = buildWorkTrackingNotificationContent(emptyList(), milestone, nowMillis = 2_000L)

        assertEquals("正在连接 Work Core", content.text)
        assertNull(content.expandedText)
        assertNull(content.targetSessionId)
    }

    private fun session(id: String, repoName: String = id) = PhoneWorkSession(
        id = id,
        runnerId = "runner",
        repoId = "repo-$id",
        repoName = repoName,
        model = "gpt-5.6",
        reasoningEffort = "high",
        status = "RUNNING",
        createdAt = "2026-07-21T00:00:00.000Z",
        updatedAt = "2026-07-21T00:00:00.000Z",
    )
}
