package me.rerere.rikkahub.data.today

import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskSource
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import me.rerere.rikkahub.data.work.PhoneWorkSession
import me.rerere.rikkahub.data.db.entity.AssistantTaskEntity
import me.rerere.rikkahub.data.task.AssistantTaskStatus
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
        assertFalse(snapshot.workConfigured)
    }

    @Test
    fun `work connection is exposed without turning shortcut into attention`() {
        val snapshot = buildTodaySnapshot(
            sessions = emptyList(),
            tasks = emptyList(),
            plans = emptyList(),
            nowMillis = now,
            workConfigured = true,
        )

        assertTrue(snapshot.workConfigured)
        assertTrue(snapshot.isEmpty)
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
    fun `today actions exclude undated inbox tasks and only overdue timed items`() {
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

        assertEquals(2, snapshot.agendaActionCount)
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

    @Test
    fun `assistant attention is ordered before agenda and independent work reminders`() {
        val snapshot = buildTodaySnapshot(
            sessions = listOf(session("work", "WAITING_FOR_USER")),
            tasks = listOf(task("agenda", dueAt = now + 60_000)),
            plans = emptyList(),
            nowMillis = now,
            assistantTasks = listOf(
                assistantTask("running", AssistantTaskStatus.RUNNING),
                assistantTask("failed", AssistantTaskStatus.FAILED_RETRYABLE),
                assistantTask("waiting", AssistantTaskStatus.WAITING_FOR_INPUT),
            ),
            zoneId = zone,
        )

        assertEquals(
            listOf("waiting", "failed", "running"),
            snapshot.items.filterIsInstance<TodayItem.AssistantTask>().map { it.task.id },
        )
        assertTrue(snapshot.items[3] is TodayItem.Agenda)
        assertTrue(snapshot.items[4] is TodayItem.WorkAttention)
    }

    @Test
    fun `only local-day completed tasks appear in folded history`() {
        val yesterday = assistantTask("old", AssistantTaskStatus.COMPLETED).copy(
            finishedAt = now - 24 * 60 * 60 * 1_000L,
        )
        val today = assistantTask("today", AssistantTaskStatus.COMPLETED).copy(finishedAt = now)

        val snapshot = buildTodaySnapshot(
            sessions = emptyList(),
            tasks = emptyList(),
            plans = emptyList(),
            nowMillis = now,
            assistantTasks = listOf(yesterday, today),
            zoneId = zone,
        )

        assertEquals(listOf("today"), snapshot.completedItems.map { it.task.id })
    }

    @Test
    fun `retryable tasks disappear after retention window`() {
        val expired = assistantTask("expired", AssistantTaskStatus.FAILED_RETRYABLE).copy(
            updatedAt = now - 24 * 60 * 60 * 1_000L,
        )
        val fresh = assistantTask("fresh", AssistantTaskStatus.FAILED_RETRYABLE).copy(
            updatedAt = now - 24 * 60 * 60 * 1_000L + 1,
        )

        val snapshot = buildTodaySnapshot(
            sessions = emptyList(),
            tasks = emptyList(),
            plans = emptyList(),
            nowMillis = now,
            assistantTasks = listOf(expired, fresh),
            zoneId = zone,
        )

        assertEquals(
            listOf("fresh"),
            snapshot.items.filterIsInstance<TodayItem.AssistantTask>().map { it.task.id },
        )
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

    private fun assistantTask(id: String, status: AssistantTaskStatus) = AssistantTaskEntity(
        id = id,
        title = id,
        status = status.name,
        attempt = 1,
        conversationId = "00000000-0000-0000-0000-000000000001",
        anchorMessageId = null,
        anchorNodeId = null,
        summary = null,
        resultKind = null,
        resultRef = null,
        errorCode = null,
        attentionReason = null,
        createdAt = now,
        updatedAt = now,
        startedAt = now,
        finishedAt = if (status == AssistantTaskStatus.COMPLETED) now else null,
    )
}
