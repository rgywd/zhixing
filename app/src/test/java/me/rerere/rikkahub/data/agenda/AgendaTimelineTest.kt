package me.rerere.rikkahub.data.agenda

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import me.rerere.rikkahub.data.model.AgendaPlan
import me.rerere.rikkahub.data.model.AgendaPlanSource
import me.rerere.rikkahub.data.model.AgendaPlanStatus
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskSource
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaTimelineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 7, 25)

    @Test
    fun `future sources share one chronological timeline and all-day events start at local day boundary`() {
        val allDayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val allDayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val taskAt = atHour(9)
        val planAt = atHour(10)
        val calendarAt = atHour(11)

        val timeline = buildAgendaFutureTimeline(
            futureTasks = listOf(task("task", taskAt)),
            upcomingPlans = listOf(plan("plan", planAt)),
            calendarEvents = listOf(
                calendarEvent(id = 2, startAt = calendarAt, endAt = calendarAt + 60_000),
                calendarEvent(id = 1, startAt = allDayStart, endAt = allDayEnd, allDay = true),
            ),
            zoneId = zone,
            nowMillis = atHour(8),
        )

        assertEquals(
            listOf(
                "calendar:1:$allDayStart",
                "task:task",
                "plan:plan",
                "calendar:2:$calendarAt",
            ),
            timeline.map { it.stableId },
        )
        val allDayTime = (timeline.first() as AgendaTimelineEntry.Calendar).time
        assertTrue(allDayTime is AgendaTimelineTime.AllDay)
        allDayTime as AgendaTimelineTime.AllDay
        assertEquals(date, allDayTime.startDate)
        assertEquals(date.plusDays(1), allDayTime.endDateExclusive)
    }

    @Test
    fun `equal timestamps use deterministic source and stable-id tie breakers`() {
        val timestamp = atHour(9)
        val plans = listOf(plan("plan", timestamp))
        val events = listOf(calendarEvent(id = 7, startAt = timestamp, endAt = timestamp + 1))

        val first = buildAgendaFutureTimeline(
            futureTasks = listOf(task("b", timestamp), task("a", timestamp)),
            upcomingPlans = plans,
            calendarEvents = events,
            zoneId = zone,
            nowMillis = atHour(8),
        )
        val second = buildAgendaFutureTimeline(
            futureTasks = listOf(task("a", timestamp), task("b", timestamp)),
            upcomingPlans = plans,
            calendarEvents = events,
            zoneId = zone,
            nowMillis = atHour(8),
        )

        val expected = listOf("task:a", "task:b", "plan:plan", "calendar:7:$timestamp")
        assertEquals(expected, first.map { it.stableId })
        assertEquals(expected, second.map { it.stableId })
    }

    @Test
    fun `entries without a timeline time are omitted instead of receiving a fabricated timestamp`() {
        val timeline = buildAgendaFutureTimeline(
            futureTasks = listOf(task("inbox", null)),
            upcomingPlans = listOf(plan("unscheduled", null)),
            calendarEvents = emptyList(),
            zoneId = zone,
            nowMillis = atHour(8),
        )

        assertTrue(timeline.isEmpty())
    }

    @Test
    fun `ended timed events are omitted while ongoing and current all-day events remain`() {
        val now = atHour(14)
        val allDayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val allDayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        val timeline = buildAgendaFutureTimeline(
            futureTasks = emptyList(),
            upcomingPlans = emptyList(),
            calendarEvents = listOf(
                calendarEvent(id = 1, startAt = atHour(9), endAt = atHour(10)),
                calendarEvent(id = 2, startAt = atHour(13), endAt = atHour(15)),
                calendarEvent(id = 3, startAt = allDayStart, endAt = allDayEnd, allDay = true),
            ),
            zoneId = zone,
            nowMillis = now,
        )

        assertEquals(
            listOf("calendar:3:$allDayStart", "calendar:2:${atHour(13)}"),
            timeline.map { it.stableId },
        )
    }

    private fun atHour(hour: Int): Long = date.atTime(hour, 0)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    private fun task(id: String, dueAt: Long?) = AgendaTask(
        id = id,
        title = id,
        note = "",
        status = AgendaTaskStatus.PENDING,
        dueAt = dueAt,
        reminderAt = null,
        source = AgendaTaskSource.MANUAL,
        conversationId = null,
        createdAt = 1,
        updatedAt = 1,
        completedAt = null,
    )

    private fun plan(id: String, nextAt: Long?) = AgendaPlanProjection(
        value = AgendaPlanWithStages(
            plan = AgendaPlan(
                id = id,
                title = id,
                note = "",
                location = "",
                status = AgendaPlanStatus.ACTIVE,
                eventAt = nextAt,
                source = AgendaPlanSource.MANUAL,
                sourceReference = null,
                conversationId = null,
                createdAt = 1,
                updatedAt = 1,
                completedAt = null,
            ),
            stages = emptyList(),
        ),
        currentStage = null,
        phase = AgendaPlanPhase.UPCOMING,
        nextAt = nextAt,
    )

    private fun calendarEvent(
        id: Long,
        startAt: Long,
        endAt: Long,
        allDay: Boolean = false,
    ) = DeviceCalendarEvent(
        id = id,
        title = "event-$id",
        description = "",
        location = "",
        startAt = startAt,
        endAt = endAt,
        allDay = allDay,
        calendarName = "calendar",
    )
}
