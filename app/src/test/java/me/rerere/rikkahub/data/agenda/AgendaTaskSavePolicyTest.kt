package me.rerere.rikkahub.data.agenda

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgendaTaskSavePolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 7, 24, 10, 0, 0, 0, zone)

    @Test
    fun `quick input is resolved when no due time was selected`() {
        val result = resolveAgendaTaskSave(
            input = AgendaTaskSaveInput(
                title = "明天 20:00 给父母打电话",
                note = "",
                dueAt = null,
                reminderEnabled = false,
                originalDueAt = null,
                originalReminderAt = null,
                quickInputEnabled = true,
            ),
            now = now,
        )

        assertEquals("给父母打电话", result.title)
        assertEquals(
            ZonedDateTime.of(2026, 7, 25, 20, 0, 0, 0, zone).toInstant().toEpochMilli(),
            result.dueAt,
        )
        assertNull(result.reminderAt)
    }

    @Test
    fun `editing title preserves an independent reminder`() {
        val originalDueAt = now.plusDays(2).withHour(20).toInstant().toEpochMilli()
        val originalReminderAt = now.plusDays(2).withHour(18).toInstant().toEpochMilli()

        val result = resolveAgendaTaskSave(
            input = AgendaTaskSaveInput(
                title = "给父母打视频电话",
                note = "确认周末安排",
                dueAt = originalDueAt,
                reminderEnabled = true,
                originalDueAt = originalDueAt,
                originalReminderAt = originalReminderAt,
                quickInputEnabled = false,
            ),
            now = now,
        )

        assertEquals("给父母打视频电话", result.title)
        assertEquals(originalDueAt, result.dueAt)
        assertEquals(originalReminderAt, result.reminderAt)
    }

    @Test
    fun `editing title preserves an independent reminder without a due time`() {
        val originalReminderAt = now.plusHours(3).toInstant().toEpochMilli()

        val result = resolveAgendaTaskSave(
            input = AgendaTaskSaveInput(
                title = "出门前带伞",
                note = "",
                dueAt = null,
                reminderEnabled = true,
                originalDueAt = null,
                originalReminderAt = originalReminderAt,
                quickInputEnabled = false,
            ),
            now = now,
        )

        assertNull(result.dueAt)
        assertEquals(originalReminderAt, result.reminderAt)
    }

    @Test
    fun `changing due time only moves a reminder that was coupled to the old due time`() {
        val originalDueAt = now.plusDays(2).withHour(20).toInstant().toEpochMilli()
        val updatedDueAt = now.plusDays(3).withHour(20).toInstant().toEpochMilli()

        val coupled = resolveAgendaTaskSave(
            input = AgendaTaskSaveInput(
                title = "缴费",
                note = "",
                dueAt = updatedDueAt,
                reminderEnabled = true,
                originalDueAt = originalDueAt,
                originalReminderAt = originalDueAt,
                quickInputEnabled = false,
            ),
            now = now,
        )
        val independentReminderAt = now.plusDays(2).withHour(18).toInstant().toEpochMilli()
        val independent = resolveAgendaTaskSave(
            input = AgendaTaskSaveInput(
                title = "缴费",
                note = "",
                dueAt = updatedDueAt,
                reminderEnabled = true,
                originalDueAt = originalDueAt,
                originalReminderAt = independentReminderAt,
                quickInputEnabled = false,
            ),
            now = now,
        )

        assertEquals(updatedDueAt, coupled.reminderAt)
        assertEquals(independentReminderAt, independent.reminderAt)
    }

    @Test
    fun `editing and clearing a due time does not reparse the existing title`() {
        val originalDueAt = now.plusDays(1).withHour(20).toInstant().toEpochMilli()

        val result = resolveAgendaTaskSave(
            input = AgendaTaskSaveInput(
                title = "明天 20:00 给父母打电话",
                note = "",
                dueAt = null,
                reminderEnabled = false,
                originalDueAt = originalDueAt,
                originalReminderAt = null,
                quickInputEnabled = false,
            ),
            now = now,
        )

        assertEquals("明天 20:00 给父母打电话", result.title)
        assertNull(result.dueAt)
    }

    @Test
    fun `moving an overdue coupled reminder to a future due time keeps it enabled`() {
        val overdueAt = now.minusHours(1).toInstant().toEpochMilli()
        val futureAt = now.plusHours(3).toInstant().toEpochMilli()

        val result = resolveAgendaTaskSave(
            input = AgendaTaskSaveInput(
                title = "补交材料",
                note = "",
                dueAt = futureAt,
                reminderEnabled = true,
                originalDueAt = overdueAt,
                originalReminderAt = overdueAt,
                quickInputEnabled = false,
            ),
            now = now,
        )

        assertEquals(futureAt, result.reminderAt)
    }
}
