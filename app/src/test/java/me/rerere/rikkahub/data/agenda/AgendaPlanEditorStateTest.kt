package me.rerere.rikkahub.data.agenda

import me.rerere.rikkahub.data.model.AgendaPlan
import me.rerere.rikkahub.data.model.AgendaPlanSource
import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStageStatus
import me.rerere.rikkahub.data.model.AgendaPlanStatus
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaPlanEditorStateTest {
    @Test
    fun `create submission maps ordered absolute and relative stage fields`() {
        val state = newAgendaPlanEditorState().copy(
            title = " 公司年会 ",
            note = " 年度活动 ",
            location = " 上海 ",
            eventAt = EVENT_AT,
            stages = listOf(
                AgendaPlanStageEditorState(
                    title = " 买票 ",
                    scheduled = AgendaPlanTimeEditorValue.absolute(EVENT_AT - days(20)),
                    due = AgendaPlanTimeEditorValue.relative("-21600"),
                ),
                AgendaPlanStageEditorState(
                    title = "确认天气",
                    trigger = AgendaPlanTimeEditorValue.relative("-4320"),
                    reminder = AgendaPlanTimeEditorValue.none(),
                ),
            ),
        )

        val result = evaluateAgendaPlanEditor(state)
        val submission = result.submission as AgendaPlanEditorSubmission.Create

        assertTrue(result.issues.isEmpty())
        assertEquals("公司年会", submission.parent.title)
        assertEquals("年度活动", submission.parent.note)
        assertEquals("上海", submission.parent.location)
        assertEquals(EVENT_AT, submission.parent.eventAt)
        assertEquals(listOf("买票", "确认天气"), submission.stages.map { it.title })
        assertEquals(EVENT_AT - days(20), submission.stages[0].scheduledAt)
        assertNull(submission.stages[0].scheduledOffsetMinutes)
        assertNull(submission.stages[0].dueAt)
        assertEquals(-21600L, submission.stages[0].dueOffsetMinutes)
        assertEquals(-4320L, submission.stages[1].triggerOffsetMinutes)
    }

    @Test
    fun `existing relative timing stays relative even though model also stores resolved absolute time`() {
        val plan = existingPlan()

        val state = plan.toAgendaPlanEditorState()
        val relative = state.stages.first().trigger

        assertEquals(AgendaPlanTimeMode.RELATIVE, relative.mode)
        assertEquals("-21600", relative.offsetMinutes)
        assertNull(relative.absoluteAt)
    }

    @Test
    fun `edit submission preserves plan reference and stage ids in position order`() {
        val state = existingPlan().toAgendaPlanEditorState().copy(
            title = "更新后的年会",
            stages = existingPlan().toAgendaPlanEditorState().stages.mapIndexed { index, stage ->
                stage.copy(note = "阶段 ${index + 1}")
            },
        )

        val result = evaluateAgendaPlanEditor(state)
        val submission = result.submission as AgendaPlanEditorSubmission.Update

        assertTrue(result.issues.isEmpty())
        assertEquals("plan-1", submission.planId)
        assertEquals("chat-message-1", submission.parent.sourceReference)
        assertEquals(listOf("stage-1", "stage-2"), submission.stageUpdates.map { it.stageId })
        assertEquals(listOf("阶段 1", "阶段 2"), submission.stageUpdates.map { it.draft.note })
    }

    @Test
    fun `validation reports missing parent fields stage title and malformed relative offset`() {
        val state = newAgendaPlanEditorState().copy(
            title = " ",
            eventAt = null,
            stages = listOf(
                AgendaPlanStageEditorState(
                    title = "",
                    reminder = AgendaPlanTimeEditorValue.relative("提前一天"),
                )
            ),
        )

        val result = evaluateAgendaPlanEditor(state)

        assertNull(result.submission)
        assertEquals(
            setOf(
                AgendaPlanEditorIssueCode.PLAN_TITLE_REQUIRED,
                AgendaPlanEditorIssueCode.EVENT_TIME_REQUIRED,
                AgendaPlanEditorIssueCode.STAGE_TITLE_REQUIRED,
                AgendaPlanEditorIssueCode.INVALID_RELATIVE_OFFSET,
            ),
            result.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun `edit mode rejects stage additions removals and reordering`() {
        val initial = existingPlan().toAgendaPlanEditorState()

        val removed = evaluateAgendaPlanEditor(initial.copy(stages = initial.stages.dropLast(1)))
        val reordered = evaluateAgendaPlanEditor(initial.copy(stages = initial.stages.reversed()))
        val added = evaluateAgendaPlanEditor(
            initial.copy(stages = initial.stages + AgendaPlanStageEditorState(title = "额外阶段"))
        )

        assertFalse(removed.isValid)
        assertFalse(reordered.isValid)
        assertFalse(added.isValid)
        assertTrue(removed.issues.any { it.code == AgendaPlanEditorIssueCode.EDIT_STAGE_TOPOLOGY_CHANGED })
        assertTrue(reordered.issues.any { it.code == AgendaPlanEditorIssueCode.EDIT_STAGE_TOPOLOGY_CHANGED })
        assertTrue(added.issues.any { it.code == AgendaPlanEditorIssueCode.EDIT_STAGE_TOPOLOGY_CHANGED })
    }

    private fun existingPlan() = AgendaPlanWithStages(
        plan = AgendaPlan(
            id = "plan-1",
            title = "公司年会",
            note = "",
            location = "上海",
            status = AgendaPlanStatus.ACTIVE,
            eventAt = EVENT_AT,
            source = AgendaPlanSource.CHAT,
            sourceReference = "chat-message-1",
            conversationId = "conversation-1",
            createdAt = 1,
            updatedAt = 2,
            completedAt = null,
        ),
        stages = listOf(
            stage(
                id = "stage-2",
                position = 1,
                title = "确认天气",
                scheduledAt = EVENT_AT - days(3),
            ),
            stage(
                id = "stage-1",
                position = 0,
                title = "购买车票",
                triggerAt = EVENT_AT - days(15),
                triggerOffsetMinutes = -21600,
            ),
        ),
    )

    private fun stage(
        id: String,
        position: Int,
        title: String,
        scheduledAt: Long? = null,
        triggerAt: Long? = null,
        triggerOffsetMinutes: Long? = null,
    ) = AgendaPlanStage(
        id = id,
        planId = "plan-1",
        title = title,
        note = "",
        status = AgendaPlanStageStatus.PENDING,
        position = position,
        scheduledAt = scheduledAt,
        dueAt = null,
        triggerAt = triggerAt,
        reminderAt = null,
        scheduledOffsetMinutes = null,
        dueOffsetMinutes = null,
        triggerOffsetMinutes = triggerOffsetMinutes,
        reminderOffsetMinutes = null,
        createdAt = 1,
        updatedAt = 2,
        completedAt = null,
    )

    private fun days(value: Long) = value * 24 * 60 * 60 * 1000

    companion object {
        private const val EVENT_AT = 1_800_000_000_000L
    }
}
