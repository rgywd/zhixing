package me.rerere.rikkahub.data.today

import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskSource
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import me.rerere.rikkahub.data.work.PhoneWorkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TodayOverviewProviderTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 7, 24, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `empty inputs produce empty snapshot`() {
        val snapshot = buildTodaySnapshot(emptyList(), emptyList(), emptyList(), now)

        assertTrue(snapshot.isEmpty)
        assertEquals(0, snapshot.agendaActionCount)
        assertEquals(0, snapshot.agendaOverdueCount)
    }

    @Test
    fun `only waiting sessions are kept in dao order`() {
        val snapshot = buildTodaySnapshot(
            sessions = listOf(
                session("a", status = "WAITING_FOR_USER"),
                session("b", status = "RUNNING"),
                session("c", status = "WAITING_FOR_USER"),
            ),
            tasks = emptyList(),
            plans = emptyList(),
            nowMillis = now,
        )

        assertEquals(listOf("a", "c"), snapshot.waitingSessions.map { it.id })
        assertFalse(snapshot.isEmpty)
    }

    @Test
    fun `overdue count only includes actions due before now`() {
        val snapshot = buildTodaySnapshot(
            sessions = emptyList(),
            tasks = listOf(
                task("overdue", dueAt = now - 60_000),
                task("today", dueAt = now + 60_000),
                task("undated", dueAt = null),
                task("future", dueAt = now + 2 * 24 * 60 * 60 * 1000),
            ),
            plans = emptyList(),
            nowMillis = now,
        )

        assertEquals(3, snapshot.agendaActionCount)
        assertEquals(1, snapshot.agendaOverdueCount)
    }

    @Test
    fun `agenda actions alone make snapshot non-empty without waiting sessions`() {
        val snapshot = buildTodaySnapshot(
            sessions = emptyList(),
            tasks = listOf(task("call", dueAt = now + 60_000)),
            plans = emptyList(),
            nowMillis = now,
        )

        assertFalse(snapshot.isEmpty)
        assertTrue(snapshot.waitingSessions.isEmpty())
    }

    private fun session(id: String, status: String) = PhoneWorkSession(
        id = id,
        runnerId = "runner",
        repoId = "repo",
        repoName = "repo",
        title = id,
        model = "gpt-5",
        reasoningEffort = "medium",
        status = status,
        createdAt = "2026-07-24T00:00:00Z",
        updatedAt = "2026-07-24T00:00:00Z",
    )

    private fun task(id: String, dueAt: Long?) = AgendaTask(
        id = id,
        title = id,
        note = "",
        status = AgendaTaskStatus.PENDING,
        dueAt = dueAt,
        reminderAt = null,
        source = AgendaTaskSource.MANUAL,
        conversationId = null,
        createdAt = now,
        updatedAt = now,
        completedAt = null,
    )
}
