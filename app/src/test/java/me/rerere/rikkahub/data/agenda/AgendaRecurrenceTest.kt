package me.rerere.rikkahub.data.agenda

import java.time.ZoneId
import java.time.ZonedDateTime
import me.rerere.rikkahub.data.model.AgendaRecurrence
import me.rerere.rikkahub.data.model.AgendaRecurrenceFrequency
import org.junit.Assert.assertEquals
import org.junit.Test

class AgendaRecurrenceTest {
    @Test
    fun `daily recurrence preserves local clock time across daylight saving`() {
        val zone = ZoneId.of("America/New_York")
        val occurrence = ZonedDateTime.of(2026, 3, 7, 8, 0, 0, 0, zone)
        val after = occurrence.plusHours(1)

        val next = nextAgendaOccurrence(
            occurrenceAt = occurrence.toInstant().toEpochMilli(),
            recurrence = AgendaRecurrence(AgendaRecurrenceFrequency.DAILY),
            afterExclusive = after.toInstant().toEpochMilli(),
            zone = zone,
        )

        assertEquals(
            ZonedDateTime.of(2026, 3, 8, 8, 0, 0, 0, zone).toInstant().toEpochMilli(),
            next,
        )
    }

    @Test
    fun `completion skips missed occurrences and preserves reminder offset`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val dueAt = ZonedDateTime.of(2026, 8, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val reminderAt = dueAt - 30 * 60 * 1_000L
        val after = ZonedDateTime.of(2026, 8, 3, 9, 0, 0, 0, zone).toInstant().toEpochMilli()

        val advanced = advanceAgendaRecurrence(
            recurrence = AgendaRecurrence(AgendaRecurrenceFrequency.DAILY),
            dueAt = dueAt,
            reminderAt = reminderAt,
            afterExclusive = after,
            zone = zone,
        )

        val expectedDue = ZonedDateTime.of(2026, 8, 4, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedDue, advanced.dueAt)
        assertEquals(expectedDue - 30 * 60 * 1_000L, advanced.reminderAt)
    }

    @Test
    fun `calendar recurrence uses provider compatible rule and duration`() {
        val recurrence = AgendaRecurrence(AgendaRecurrenceFrequency.WEEKLY, interval = 2)

        assertEquals("FREQ=WEEKLY;INTERVAL=2", recurrence.toRRule())
        assertEquals("PT3600S", calendarEventDuration(0L, 3_600_000L, allDay = false))
        assertEquals("P2D", calendarEventDuration(0L, 172_800_000L, allDay = true))
    }
}
