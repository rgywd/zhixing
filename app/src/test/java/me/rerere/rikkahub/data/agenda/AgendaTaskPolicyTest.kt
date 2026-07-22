package me.rerere.rikkahub.data.agenda

import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskSource
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AgendaTaskPolicyTest {
    private val now = ZonedDateTime.of(2026, 7, 22, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    @Test
    fun classifiesUndatedAndTimedTasks() {
        assertEquals(AgendaTaskBucket.TODAY, task(null).let { agendaTaskBucket(it, now) })
        assertEquals(AgendaTaskBucket.OVERDUE, task(now.minusMinutes(1).toInstant().toEpochMilli()).let { agendaTaskBucket(it, now) })
        assertEquals(AgendaTaskBucket.TODAY, task(now.plusHours(2).toInstant().toEpochMilli()).let { agendaTaskBucket(it, now) })
        assertEquals(AgendaTaskBucket.UPCOMING, task(now.plusDays(10).toInstant().toEpochMilli()).let { agendaTaskBucket(it, now) })
    }

    @Test
    fun completedAndCancelledDoNotDependOnDueTime() {
        assertEquals(AgendaTaskBucket.COMPLETED, agendaTaskBucket(task(null, AgendaTaskStatus.COMPLETED), now))
        assertEquals(AgendaTaskBucket.HIDDEN, agendaTaskBucket(task(null, AgendaTaskStatus.CANCELLED), now))
    }

    private fun task(dueAt: Long?, status: AgendaTaskStatus = AgendaTaskStatus.PENDING) = AgendaTask(
        id = "task",
        title = "测试事项",
        note = "",
        status = status,
        dueAt = dueAt,
        reminderAt = null,
        source = AgendaTaskSource.MANUAL,
        conversationId = null,
        createdAt = 1,
        updatedAt = 1,
        completedAt = null,
    )
}
