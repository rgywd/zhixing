package me.rerere.rikkahub.data.status

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

class LocalStatusSourcesTest {
    @Test
    fun longHorizonPlanContributesItsCurrentStageToStatusContext() {
        val now = 1_774_406_400_000L
        val eventAt = now + 20L * 24 * 60 * 60 * 1_000
        val triggerAt = now + 2L * 24 * 60 * 60 * 1_000
        val plan = AgendaPlanWithStages(
            plan = AgendaPlan(
                id = "annual-party",
                title = "公司年会",
                note = "",
                location = "上海",
                status = AgendaPlanStatus.ACTIVE,
                eventAt = eventAt,
                source = AgendaPlanSource.CHAT,
                sourceReference = null,
                conversationId = "conversation",
                createdAt = now,
                updatedAt = now,
                completedAt = null,
            ),
            stages = listOf(
                AgendaPlanStage(
                    id = "buy-ticket",
                    planId = "annual-party",
                    title = "购买车票",
                    note = "",
                    status = AgendaPlanStageStatus.PENDING,
                    position = 0,
                    scheduledAt = null,
                    dueAt = triggerAt,
                    triggerAt = triggerAt,
                    reminderAt = triggerAt,
                    scheduledOffsetMinutes = null,
                    dueOffsetMinutes = -18L * 24 * 60,
                    triggerOffsetMinutes = -18L * 24 * 60,
                    reminderOffsetMinutes = -18L * 24 * 60,
                    createdAt = now,
                    updatedAt = now,
                    completedAt = null,
                )
            ),
        )

        val facts = buildMyStatusAgendaFacts(
            tasks = emptyList(),
            plans = listOf(plan),
            nowEpochMillis = now,
        )

        assertEquals(1, facts.pendingCount)
        assertEquals(0, facts.overdueCount)
        assertEquals("公司年会 · 购买车票", facts.nextItems.single().title)
        assertTrue(facts.nextItems.single().dueAt != null)
    }

    @Test
    fun agendaItemsExposeStableEvidenceAndDistinguishUndatedFromDueSoon() {
        val now = 1_774_406_400_000L
        val tasks = listOf(
            task(id = "undated", title = "整理右侧面板", now = now, dueAt = null),
            task(
                id = "deadline",
                title = "提交报名材料",
                now = now,
                dueAt = now + 45 * 60 * 1_000L,
            ),
        )

        val facts = buildMyStatusAgendaFacts(
            tasks = tasks,
            plans = emptyList(),
            nowEpochMillis = now,
        )

        assertEquals(2, facts.pendingCount)
        assertEquals(MyStatusAgendaTiming.DUE_SOON, facts.nextItems[0].timing)
        assertEquals("agenda.item.task:deadline", facts.nextItems[0].evidenceId)
        assertEquals(MyStatusAgendaTiming.UNDATED, facts.nextItems[1].timing)
        assertEquals("agenda.item.task:undated", facts.nextItems[1].evidenceId)
    }

    private fun task(
        id: String,
        title: String,
        now: Long,
        dueAt: Long?,
    ) = AgendaTask(
        id = id,
        title = title,
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
