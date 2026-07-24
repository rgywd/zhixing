package me.rerere.rikkahub.data.agenda

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import me.rerere.rikkahub.data.model.AgendaTask

sealed interface AgendaTimelineTime {
    data class Timed(
        val startAt: Long,
        val endAt: Long? = null,
    ) : AgendaTimelineTime {
        init {
            require(endAt == null || endAt >= startAt) { "结束时间不能早于开始时间" }
        }
    }

    data class AllDay(
        val startDate: LocalDate,
        val endDateExclusive: LocalDate,
    ) : AgendaTimelineTime {
        init {
            require(endDateExclusive.isAfter(startDate)) { "全天事项结束日期必须晚于开始日期" }
        }
    }
}

sealed interface AgendaTimelineEntry {
    val stableId: String
    val time: AgendaTimelineTime

    data class Task(
        val task: AgendaTask,
        override val time: AgendaTimelineTime.Timed,
    ) : AgendaTimelineEntry {
        override val stableId: String = "task:${task.id}"
    }

    data class Plan(
        val projection: AgendaPlanProjection,
        override val time: AgendaTimelineTime.Timed,
    ) : AgendaTimelineEntry {
        override val stableId: String = "plan:${projection.value.plan.id}"
    }

    data class Calendar(
        val event: DeviceCalendarEvent,
        override val time: AgendaTimelineTime,
    ) : AgendaTimelineEntry {
        override val stableId: String = "calendar:${event.id}:${event.startAt}"
    }
}

fun buildAgendaFutureTimeline(
    futureTasks: List<AgendaTask>,
    upcomingPlans: List<AgendaPlanProjection>,
    calendarEvents: List<DeviceCalendarEvent>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    nowMillis: Long = System.currentTimeMillis(),
): List<AgendaTimelineEntry> = buildList {
    futureTasks.forEach { task ->
        task.dueAt?.let { dueAt ->
            add(AgendaTimelineEntry.Task(task, AgendaTimelineTime.Timed(startAt = dueAt)))
        }
    }
    upcomingPlans.forEach { plan ->
        plan.nextAt?.let { nextAt ->
            add(AgendaTimelineEntry.Plan(plan, AgendaTimelineTime.Timed(startAt = nextAt)))
        }
    }
    calendarEvents.forEach { event ->
        val time = event.toAgendaTimelineTime()
        if (time.isActiveOrFuture(nowMillis, zoneId)) {
            add(AgendaTimelineEntry.Calendar(event, time))
        }
    }
}.sortedWith(
    compareBy<AgendaTimelineEntry>(
        { it.time.sortAt(zoneId) },
        { if (it.time is AgendaTimelineTime.AllDay) 0 else 1 },
        { it.sourceRank() },
        AgendaTimelineEntry::stableId,
    )
)

private fun DeviceCalendarEvent.toAgendaTimelineTime(): AgendaTimelineTime {
    if (!allDay) {
        return AgendaTimelineTime.Timed(
            startAt = startAt,
            endAt = endAt.takeIf { it >= startAt },
        )
    }

    val startDate = Instant.ofEpochMilli(startAt).atZone(ZoneOffset.UTC).toLocalDate()
    val providerEndDate = Instant.ofEpochMilli(endAt).atZone(ZoneOffset.UTC).toLocalDate()
    return AgendaTimelineTime.AllDay(
        startDate = startDate,
        endDateExclusive = providerEndDate.takeIf { it.isAfter(startDate) } ?: startDate.plusDays(1),
    )
}

private fun AgendaTimelineTime.sortAt(zoneId: ZoneId): Long = when (this) {
    is AgendaTimelineTime.Timed -> startAt
    is AgendaTimelineTime.AllDay -> startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
}

private fun AgendaTimelineTime.isActiveOrFuture(nowMillis: Long, zoneId: ZoneId): Boolean = when (this) {
    is AgendaTimelineTime.Timed -> (endAt ?: startAt) > nowMillis
    is AgendaTimelineTime.AllDay ->
        endDateExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli() > nowMillis
}

private fun AgendaTimelineEntry.sourceRank(): Int = when (this) {
    is AgendaTimelineEntry.Task -> 0
    is AgendaTimelineEntry.Plan -> 1
    is AgendaTimelineEntry.Calendar -> 2
}
