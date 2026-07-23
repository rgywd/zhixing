package me.rerere.rikkahub.data.status

import me.rerere.rikkahub.data.model.AgendaPlan
import me.rerere.rikkahub.data.model.AgendaPlanSource
import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStageStatus
import me.rerere.rikkahub.data.model.AgendaPlanStatus
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
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
}
