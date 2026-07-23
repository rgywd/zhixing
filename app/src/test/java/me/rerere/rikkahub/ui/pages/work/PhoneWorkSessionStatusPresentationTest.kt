package me.rerere.rikkahub.ui.pages.work

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.work.PhoneWorkCatalog
import me.rerere.rikkahub.data.work.PhoneWorkEvent
import me.rerere.rikkahub.data.work.PhoneWorkRunner
import me.rerere.rikkahub.data.work.PhoneWorkSession
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneWorkSessionStatusPresentationTest {
    private val now = Instant.parse("2026-07-23T02:12:00Z")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `running session is trusted while runner lease is alive`() {
        val presentation = buildWorkSessionStatusPresentation(
            session = session(status = "RUNNING"),
            catalog = catalog(
                runner = runner(online = true, leaseUntil = "2026-07-23T02:12:30Z"),
                refreshedAtMillis = now.minusSeconds(5).toEpochMilli(),
            ),
            events = listOf(runState(seq = 1, status = "RUNNING", at = "2026-07-23T02:10:00Z")),
            now = now,
            zoneId = utc,
        )

        assertEquals("运行中 · 开发机在线", presentation?.headline)
        assertEquals("本轮开始 02:10 · 2 分钟前更新", presentation?.detail)
        assertEquals(WorkSessionStatusKind.ACTIVE, presentation?.kind)
    }

    @Test
    fun `expired runner lease makes stale running state untrusted`() {
        val presentation = buildWorkSessionStatusPresentation(
            session = session(status = "RUNNING"),
            catalog = catalog(
                runner = runner(online = true, leaseUntil = "2026-07-23T02:11:59Z"),
                refreshedAtMillis = now.minusSeconds(30).toEpochMilli(),
            ),
            events = listOf(runState(seq = 1, status = "RUNNING", at = "2026-07-23T02:10:00Z")),
            now = now,
            zoneId = utc,
        )

        assertEquals("连接中断 · 任务状态待确认", presentation?.headline)
        assertEquals(WorkSessionStatusKind.ATTENTION, presentation?.kind)
    }

    @Test
    fun `runner missing from a refreshed catalog is offline`() {
        val presentation = buildWorkSessionStatusPresentation(
            session = session(status = "RUNNING"),
            catalog = PhoneWorkCatalog(refreshedAtMillis = now.toEpochMilli()),
            events = emptyList(),
            now = now,
            zoneId = utc,
        )

        assertEquals("连接中断 · 任务状态待确认", presentation?.headline)
    }

    @Test
    fun `idle session remains resumable`() {
        val presentation = buildWorkSessionStatusPresentation(
            session = session(status = "IDLE"),
            catalog = PhoneWorkCatalog(),
            events = emptyList(),
            now = now,
            zoneId = utc,
        )

        assertEquals("本轮完成 · 可继续", presentation?.headline)
        assertEquals(WorkSessionStatusKind.IDLE, presentation?.kind)
    }

    @Test
    fun `run state timeline contains event time`() {
        assertEquals(
            "02:10  开始执行",
            workRunStateTimelineLabel(
                status = "RUNNING",
                detail = null,
                createdAt = "2026-07-23T02:10:00Z",
                zoneId = utc,
            ),
        )
        assertEquals(
            "02:12  本轮完成 · 可继续",
            workRunStateTimelineLabel(
                status = "IDLE",
                detail = "Codex 本轮已完成",
                createdAt = "2026-07-23T02:12:00Z",
                zoneId = utc,
            ),
        )
    }

    private fun session(status: String) = PhoneWorkSession(
        id = "work-1",
        runnerId = "runner-1",
        repoId = "repo-1",
        repoName = "zhixing",
        model = "gpt-5.6-sol",
        reasoningEffort = "high",
        status = status,
        createdAt = "2026-07-23T02:09:00Z",
        updatedAt = "2026-07-23T02:10:00Z",
    )

    private fun runner(online: Boolean, leaseUntil: String?) = PhoneWorkRunner(
        id = "runner-1",
        name = "Minecraft",
        version = "1",
        online = online,
        leaseUntil = leaseUntil,
    )

    private fun catalog(runner: PhoneWorkRunner, refreshedAtMillis: Long) = PhoneWorkCatalog(
        runners = listOf(runner),
        refreshedAtMillis = refreshedAtMillis,
    )

    private fun runState(seq: Long, status: String, at: String) = PhoneWorkEvent(
        sessionId = "work-1",
        seq = seq,
        id = "event-$seq",
        type = "RUN_STATE",
        payload = buildJsonObject { put("status", status) },
        createdAt = at,
    )
}
