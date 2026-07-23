package me.rerere.rikkahub.data.agenda

import me.rerere.rikkahub.data.model.AgendaPlan
import me.rerere.rikkahub.data.model.AgendaPlanSource
import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStageStatus
import me.rerere.rikkahub.data.model.AgendaPlanStatus
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskSource
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AgendaPlanPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 7, 23, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `overdue stages sort before due simple tasks and upcoming plans`() {
        val overdueStage = stage("buy-ticket", triggerAt = now - 1, dueAt = now - 1)
        val plan = plan("annual-party", listOf(overdueStage), eventAt = now + days(15))
        val task = task("call", dueAt = now + 60_000)

        val projection = buildAgendaProjection(listOf(task), listOf(plan), now, zone)

        assertEquals(listOf("stage:buy-ticket", "task:call"), projection.actions.map { it.stableId })
        assertEquals(0, projection.upcomingPlanCount)
    }

    @Test
    fun `completed first stage leaves plan waiting on the next future node`() {
        val completed = stage(
            id = "buy-ticket",
            status = AgendaPlanStageStatus.COMPLETED,
            position = 0,
            triggerAt = now - days(1),
        )
        val weather = stage("weather", position = 1, triggerAt = now + days(12))
        val projection = buildAgendaProjection(
            tasks = emptyList(),
            plans = listOf(plan("annual-party", listOf(completed, weather), eventAt = now + days(15))),
            nowMillis = now,
            zoneId = zone,
        )

        assertTrue(projection.actions.isEmpty())
        assertEquals(AgendaPlanPhase.WAITING, projection.waitingPlans.single().phase)
        assertEquals("weather", projection.waitingPlans.single().currentStage?.id)
    }

    @Test
    fun `undated simple tasks remain compatible with the current action projection`() {
        val projection = buildAgendaProjection(
            tasks = listOf(task("inbox", dueAt = null)),
            plans = emptyList(),
            nowMillis = now,
            zoneId = zone,
        )

        assertEquals("task:inbox", projection.actions.single().stableId)
    }

    @Test
    fun `relative offsets resolve from the parent event`() {
        val eventAt = now + days(15)
        assertEquals(now, resolveAgendaRelativeTime(eventAt, null, -15 * 24 * 60L))
        assertEquals(now + 1234, resolveAgendaRelativeTime(eventAt, now + 1234, null))
    }

    private fun plan(id: String, stages: List<AgendaPlanStage>, eventAt: Long) = AgendaPlanWithStages(
        plan = AgendaPlan(
            id = id,
            title = "公司年会",
            note = "",
            location = "上海",
            status = AgendaPlanStatus.ACTIVE,
            eventAt = eventAt,
            source = AgendaPlanSource.MANUAL,
            sourceReference = null,
            conversationId = null,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        ),
        stages = stages,
    )

    private fun stage(
        id: String,
        status: AgendaPlanStageStatus = AgendaPlanStageStatus.PENDING,
        position: Int = 0,
        triggerAt: Long? = null,
        dueAt: Long? = null,
    ) = AgendaPlanStage(
        id = id,
        planId = "annual-party",
        title = id,
        note = "",
        status = status,
        position = position,
        scheduledAt = null,
        dueAt = dueAt,
        triggerAt = triggerAt,
        reminderAt = null,
        scheduledOffsetMinutes = null,
        dueOffsetMinutes = null,
        triggerOffsetMinutes = null,
        reminderOffsetMinutes = null,
        createdAt = now,
        updatedAt = now,
        completedAt = if (status == AgendaPlanStageStatus.COMPLETED) now else null,
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

    private fun days(value: Long) = value * 24 * 60 * 60 * 1000
}
