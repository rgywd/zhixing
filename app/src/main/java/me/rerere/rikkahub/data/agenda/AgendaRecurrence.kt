package me.rerere.rikkahub.data.agenda

import me.rerere.rikkahub.data.model.AgendaRecurrence
import me.rerere.rikkahub.data.model.AgendaRecurrenceFrequency
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class AgendaRecurrenceAdvance(
    val dueAt: Long?,
    val reminderAt: Long?,
)

fun advanceAgendaRecurrence(
    recurrence: AgendaRecurrence,
    dueAt: Long?,
    reminderAt: Long?,
    afterExclusive: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): AgendaRecurrenceAdvance {
    val anchorAt = dueAt ?: reminderAt ?: error("周期待办必须设置截止时间或提醒时间")
    val nextAnchorAt = nextAgendaOccurrence(anchorAt, recurrence, afterExclusive, zone)
    val shift = nextAnchorAt - anchorAt
    return AgendaRecurrenceAdvance(
        dueAt = dueAt?.plus(shift),
        reminderAt = reminderAt?.plus(shift),
    )
}

fun nextAgendaOccurrence(
    occurrenceAt: Long,
    recurrence: AgendaRecurrence,
    afterExclusive: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Long {
    var candidate = Instant.ofEpochMilli(occurrenceAt).atZone(zone).advance(recurrence)
    val after = Instant.ofEpochMilli(afterExclusive)
    while (!candidate.toInstant().isAfter(after)) {
        candidate = candidate.advance(recurrence)
    }
    return candidate.toInstant().toEpochMilli()
}

fun AgendaRecurrence.toRRule(): String = "FREQ=${frequency.name};INTERVAL=$interval"

fun parseAgendaRecurrence(frequency: String?, interval: Int?): AgendaRecurrence? {
    if (frequency.isNullOrBlank()) {
        require(interval == null) { "recurrence_interval requires recurrence_frequency" }
        return null
    }
    val parsedFrequency = runCatching {
        AgendaRecurrenceFrequency.valueOf(frequency.trim().uppercase())
    }.getOrElse {
        error("recurrence_frequency must be DAILY, WEEKLY, or MONTHLY")
    }
    return AgendaRecurrence(parsedFrequency, interval ?: 1)
}

fun calendarEventDuration(startAt: Long, endAt: Long, allDay: Boolean): String {
    val durationMillis = endAt - startAt
    require(durationMillis > 0) { "Calendar event duration must be positive" }
    return if (allDay) {
        require(durationMillis % MILLIS_PER_DAY == 0L) { "All-day recurrence must span whole UTC days" }
        "P${durationMillis / MILLIS_PER_DAY}D"
    } else {
        "PT${(durationMillis + 999L) / 1_000L}S"
    }
}

private fun ZonedDateTime.advance(recurrence: AgendaRecurrence): ZonedDateTime = when (recurrence.frequency) {
    AgendaRecurrenceFrequency.DAILY -> plusDays(recurrence.interval.toLong())
    AgendaRecurrenceFrequency.WEEKLY -> plusWeeks(recurrence.interval.toLong())
    AgendaRecurrenceFrequency.MONTHLY -> plusMonths(recurrence.interval.toLong())
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
