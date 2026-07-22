package me.rerere.rikkahub.data.agenda

import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import java.time.ZonedDateTime

enum class AgendaTaskBucket {
    OVERDUE,
    TODAY,
    UPCOMING,
    COMPLETED,
    HIDDEN,
}

fun agendaTaskBucket(
    task: AgendaTask,
    now: ZonedDateTime = ZonedDateTime.now(),
): AgendaTaskBucket {
    if (task.status == AgendaTaskStatus.CANCELLED) return AgendaTaskBucket.HIDDEN
    if (task.status == AgendaTaskStatus.COMPLETED) return AgendaTaskBucket.COMPLETED
    val dueAt = task.dueAt ?: return AgendaTaskBucket.TODAY
    if (dueAt < now.toInstant().toEpochMilli()) return AgendaTaskBucket.OVERDUE
    val tomorrow = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
    return if (dueAt < tomorrow) AgendaTaskBucket.TODAY else AgendaTaskBucket.UPCOMING
}
