package me.rerere.rikkahub.ui.pages.work

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.data.work.PhoneWorkEvent
import me.rerere.rikkahub.data.work.PhoneWorkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneWorkUiPresentationTest {

    private fun session(id: String, status: String, updatedAt: String = "2026-07-23T08:00:00Z") = PhoneWorkSession(
        id = id,
        runnerId = "runner-1",
        repoId = "repo-1",
        repoName = "zhixing",
        title = "会话 $id",
        model = "gpt-5",
        reasoningEffort = "high",
        status = status,
        updatedAt = updatedAt,
        createdAt = updatedAt,
    )

    @Test
    fun `groupWorkSessions orders waiting first then active then rest and drops empty groups`() {
        val sessions = listOf(
            session("idle-1", "IDLE"),
            session("run-1", "RUNNING"),
            session("wait-1", "WAITING_FOR_USER"),
            session("queued-1", "QUEUED"),
            session("done-1", "COMPLETED"),
        )
        val groups = groupWorkSessions(sessions)
        assertEquals(listOf("waiting", "active", "rest"), groups.map { it.key })
        assertEquals(listOf("wait-1"), groups[0].sessions.map { it.id })
        assertEquals(listOf("run-1", "queued-1"), groups[1].sessions.map { it.id })
        assertEquals(listOf("idle-1", "done-1"), groups[2].sessions.map { it.id })
    }

    @Test
    fun `groupWorkSessions omits waiting and active groups when absent`() {
        val groups = groupWorkSessions(listOf(session("idle-1", "IDLE")))
        assertEquals(listOf("rest"), groups.map { it.key })
    }

    @Test
    fun `summarizeWorkSessions counts waiting and active sessions`() {
        val summary = summarizeWorkSessions(
            listOf(
                session("a", "WAITING_FOR_USER"),
                session("b", "RUNNING"),
                session("c", "QUEUED"),
                session("d", "FAILED"),
            ),
        )
        assertEquals(1, summary.waiting)
        assertEquals(2, summary.active)
    }

    @Test
    fun `relativeWorkTimeLabel formats age buckets`() {
        val now = Instant.parse("2026-07-23T12:00:00Z")
        assertEquals("刚刚", relativeWorkTimeLabel("2026-07-23T11:59:30Z", now))
        assertEquals("5 分钟前", relativeWorkTimeLabel("2026-07-23T11:55:00Z", now))
        assertEquals("3 小时前", relativeWorkTimeLabel("2026-07-23T09:00:00Z", now))
        assertEquals(
            "07-20 08:00",
            relativeWorkTimeLabel("2026-07-20T08:00:00Z", now, ZoneId.of("UTC")),
        )
        assertNull(relativeWorkTimeLabel("not-a-time", now))
    }

    private fun event(id: String, type: String, createdAt: String, payloadStatus: String? = null) = PhoneWorkEvent(
        sessionId = "s",
        seq = id.filter(Char::isDigit).toLongOrNull() ?: 0,
        id = id,
        type = type,
        payload = buildJsonObject {
            payloadStatus?.let { put("status", kotlinx.serialization.json.JsonPrimitive(it)) }
        },
        createdAt = createdAt,
    )

    @Test
    fun `buildWorkTimeline inserts day headers and dedupes repeated run states`() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-07-23T12:00:00Z")
        val events = listOf(
            event("e1", "USER_MESSAGE", "2026-07-22T09:00:00Z"),
            event("e2", "RUN_STATE", "2026-07-22T09:05:00Z", "RUNNING"),
            event("e3", "RUN_STATE", "2026-07-22T09:06:00Z", "RUNNING"),
            event("e4", "ASSISTANT_MESSAGE", "2026-07-23T08:00:00Z"),
        )
        val items = buildWorkTimeline(events, now, zone)
        assertEquals(
            listOf(
                WorkTimelineItem.DayHeader("昨天", "day:e1"),
                WorkTimelineItem.Entry(events[0]),
                WorkTimelineItem.Entry(events[1]),
                WorkTimelineItem.DayHeader("今天", "day:e4"),
                WorkTimelineItem.Entry(events[3]),
            ),
            items,
        )
    }

    @Test
    fun `buildWorkTimeline keeps distinct consecutive run states`() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2026-07-23T12:00:00Z")
        val running = event("e1", "RUN_STATE", "2026-07-23T09:05:00Z", "RUNNING")
        val idle = event("e2", "RUN_STATE", "2026-07-23T09:30:00Z", "IDLE")
        val items = buildWorkTimeline(listOf(running, idle), now, zone)
        assertEquals(
            listOf(
                WorkTimelineItem.DayHeader("今天", "day:e1"),
                WorkTimelineItem.Entry(running),
                WorkTimelineItem.Entry(idle),
            ),
            items,
        )
    }
}
