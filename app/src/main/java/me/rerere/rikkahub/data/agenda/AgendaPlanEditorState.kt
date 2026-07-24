package me.rerere.rikkahub.data.agenda

import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStageDraft
import me.rerere.rikkahub.data.model.AgendaPlanWithStages

enum class AgendaPlanTimeMode {
    NONE,
    ABSOLUTE,
    RELATIVE,
}

enum class AgendaPlanStageTimeField {
    SCHEDULED,
    DUE,
    TRIGGER,
    REMINDER,
}

data class AgendaPlanTimeEditorValue(
    val mode: AgendaPlanTimeMode = AgendaPlanTimeMode.NONE,
    val absoluteAt: Long? = null,
    val offsetMinutes: String = "",
) {
    companion object {
        fun none() = AgendaPlanTimeEditorValue()

        fun absolute(value: Long?) = AgendaPlanTimeEditorValue(
            mode = AgendaPlanTimeMode.ABSOLUTE,
            absoluteAt = value,
        )

        fun relative(value: String = "") = AgendaPlanTimeEditorValue(
            mode = AgendaPlanTimeMode.RELATIVE,
            offsetMinutes = value,
        )

        fun from(absoluteAt: Long?, offsetMinutes: Long?): AgendaPlanTimeEditorValue = when {
            offsetMinutes != null -> relative(offsetMinutes.toString())
            absoluteAt != null -> absolute(absoluteAt)
            else -> none()
        }
    }
}

data class AgendaPlanStageEditorState(
    val id: String? = null,
    val title: String = "",
    val note: String = "",
    val scheduled: AgendaPlanTimeEditorValue = AgendaPlanTimeEditorValue.none(),
    val due: AgendaPlanTimeEditorValue = AgendaPlanTimeEditorValue.none(),
    val trigger: AgendaPlanTimeEditorValue = AgendaPlanTimeEditorValue.none(),
    val reminder: AgendaPlanTimeEditorValue = AgendaPlanTimeEditorValue.none(),
) {
    fun time(field: AgendaPlanStageTimeField): AgendaPlanTimeEditorValue = when (field) {
        AgendaPlanStageTimeField.SCHEDULED -> scheduled
        AgendaPlanStageTimeField.DUE -> due
        AgendaPlanStageTimeField.TRIGGER -> trigger
        AgendaPlanStageTimeField.REMINDER -> reminder
    }

    fun withTime(
        field: AgendaPlanStageTimeField,
        value: AgendaPlanTimeEditorValue,
    ): AgendaPlanStageEditorState = when (field) {
        AgendaPlanStageTimeField.SCHEDULED -> copy(scheduled = value)
        AgendaPlanStageTimeField.DUE -> copy(due = value)
        AgendaPlanStageTimeField.TRIGGER -> copy(trigger = value)
        AgendaPlanStageTimeField.REMINDER -> copy(reminder = value)
    }
}

data class AgendaPlanEditorState(
    val planId: String? = null,
    val originalStageIds: List<String> = emptyList(),
    val title: String = "",
    val note: String = "",
    val location: String = "",
    val eventAt: Long? = null,
    val sourceReference: String? = null,
    val stages: List<AgendaPlanStageEditorState> = emptyList(),
) {
    val isEditing: Boolean
        get() = planId != null
}

data class AgendaPlanEditorParentInput(
    val title: String,
    val note: String,
    val location: String,
    val eventAt: Long,
    val sourceReference: String?,
)

data class AgendaPlanStageUpdateInput(
    val stageId: String,
    val draft: AgendaPlanStageDraft,
)

sealed interface AgendaPlanEditorSubmission {
    val parent: AgendaPlanEditorParentInput

    data class Create(
        override val parent: AgendaPlanEditorParentInput,
        val stages: List<AgendaPlanStageDraft>,
    ) : AgendaPlanEditorSubmission

    data class Update(
        val planId: String,
        override val parent: AgendaPlanEditorParentInput,
        val stageUpdates: List<AgendaPlanStageUpdateInput>,
    ) : AgendaPlanEditorSubmission
}

enum class AgendaPlanEditorIssueCode {
    PLAN_TITLE_REQUIRED,
    EVENT_TIME_REQUIRED,
    STAGE_REQUIRED,
    STAGE_TITLE_REQUIRED,
    ABSOLUTE_TIME_REQUIRED,
    INVALID_RELATIVE_OFFSET,
    EDIT_STAGE_TOPOLOGY_CHANGED,
}

data class AgendaPlanEditorIssue(
    val code: AgendaPlanEditorIssueCode,
    val stageIndex: Int? = null,
    val timeField: AgendaPlanStageTimeField? = null,
) {
    val message: String
        get() = when (code) {
            AgendaPlanEditorIssueCode.PLAN_TITLE_REQUIRED -> "请输入计划标题"
            AgendaPlanEditorIssueCode.EVENT_TIME_REQUIRED -> "请设置计划发生时间"
            AgendaPlanEditorIssueCode.STAGE_REQUIRED -> "计划至少需要一个阶段"
            AgendaPlanEditorIssueCode.STAGE_TITLE_REQUIRED -> "第 ${(stageIndex ?: 0) + 1} 个阶段缺少标题"
            AgendaPlanEditorIssueCode.ABSOLUTE_TIME_REQUIRED ->
                "第 ${(stageIndex ?: 0) + 1} 个阶段的${timeField.label()}尚未设置"
            AgendaPlanEditorIssueCode.INVALID_RELATIVE_OFFSET ->
                "第 ${(stageIndex ?: 0) + 1} 个阶段的${timeField.label()}需要填写整数分钟"
            AgendaPlanEditorIssueCode.EDIT_STAGE_TOPOLOGY_CHANGED ->
                "编辑已有计划时暂不支持增删或调整阶段顺序"
        }
}

data class AgendaPlanEditorEvaluation(
    val submission: AgendaPlanEditorSubmission?,
    val issues: List<AgendaPlanEditorIssue>,
) {
    val isValid: Boolean
        get() = submission != null && issues.isEmpty()
}

fun newAgendaPlanEditorState(): AgendaPlanEditorState = AgendaPlanEditorState(
    stages = listOf(AgendaPlanStageEditorState()),
)

fun AgendaPlanWithStages.toAgendaPlanEditorState(): AgendaPlanEditorState {
    val orderedStages = stages.sortedBy { it.position }
    return AgendaPlanEditorState(
        planId = plan.id,
        originalStageIds = orderedStages.map { it.id },
        title = plan.title,
        note = plan.note,
        location = plan.location,
        eventAt = plan.eventAt,
        sourceReference = plan.sourceReference,
        stages = orderedStages.map(AgendaPlanStage::toEditorState),
    )
}

fun evaluateAgendaPlanEditor(state: AgendaPlanEditorState): AgendaPlanEditorEvaluation {
    val issues = buildList {
        if (state.title.isBlank()) {
            add(AgendaPlanEditorIssue(AgendaPlanEditorIssueCode.PLAN_TITLE_REQUIRED))
        }
        if (state.eventAt == null) {
            add(AgendaPlanEditorIssue(AgendaPlanEditorIssueCode.EVENT_TIME_REQUIRED))
        }
        if (state.stages.isEmpty()) {
            add(AgendaPlanEditorIssue(AgendaPlanEditorIssueCode.STAGE_REQUIRED))
        }
        if (
            state.isEditing &&
            (
                state.stages.size != state.originalStageIds.size ||
                    state.stages.map { it.id } != state.originalStageIds
                )
        ) {
            add(AgendaPlanEditorIssue(AgendaPlanEditorIssueCode.EDIT_STAGE_TOPOLOGY_CHANGED))
        }
        state.stages.forEachIndexed { index, stage ->
            if (stage.title.isBlank()) {
                add(
                    AgendaPlanEditorIssue(
                        code = AgendaPlanEditorIssueCode.STAGE_TITLE_REQUIRED,
                        stageIndex = index,
                    )
                )
            }
            AgendaPlanStageTimeField.entries.forEach { field ->
                val value = stage.time(field)
                when (value.mode) {
                    AgendaPlanTimeMode.NONE -> Unit
                    AgendaPlanTimeMode.ABSOLUTE -> {
                        if (value.absoluteAt == null) {
                            add(
                                AgendaPlanEditorIssue(
                                    code = AgendaPlanEditorIssueCode.ABSOLUTE_TIME_REQUIRED,
                                    stageIndex = index,
                                    timeField = field,
                                )
                            )
                        }
                    }
                    AgendaPlanTimeMode.RELATIVE -> {
                        if (value.offsetMinutes.trim().toLongOrNull() == null) {
                            add(
                                AgendaPlanEditorIssue(
                                    code = AgendaPlanEditorIssueCode.INVALID_RELATIVE_OFFSET,
                                    stageIndex = index,
                                    timeField = field,
                                )
                            )
                        }
                    }
                }
            }
        }
    }
    val eventAt = state.eventAt
    if (issues.isNotEmpty() || eventAt == null) {
        return AgendaPlanEditorEvaluation(submission = null, issues = issues)
    }

    val parent = AgendaPlanEditorParentInput(
        title = state.title.trim(),
        note = state.note.trim(),
        location = state.location.trim(),
        eventAt = eventAt,
        sourceReference = state.sourceReference?.trim()?.takeIf { it.isNotEmpty() },
    )
    val drafts = state.stages.map(AgendaPlanStageEditorState::toDraft)
    val submission = if (state.planId == null) {
        AgendaPlanEditorSubmission.Create(parent = parent, stages = drafts)
    } else {
        AgendaPlanEditorSubmission.Update(
            planId = state.planId,
            parent = parent,
            stageUpdates = state.stages.zip(drafts).map { (stage, draft) ->
                AgendaPlanStageUpdateInput(
                    stageId = requireNotNull(stage.id),
                    draft = draft,
                )
            },
        )
    }
    return AgendaPlanEditorEvaluation(submission = submission, issues = emptyList())
}

private fun AgendaPlanStage.toEditorState() = AgendaPlanStageEditorState(
    id = id,
    title = title,
    note = note,
    scheduled = AgendaPlanTimeEditorValue.from(scheduledAt, scheduledOffsetMinutes),
    due = AgendaPlanTimeEditorValue.from(dueAt, dueOffsetMinutes),
    trigger = AgendaPlanTimeEditorValue.from(triggerAt, triggerOffsetMinutes),
    reminder = AgendaPlanTimeEditorValue.from(reminderAt, reminderOffsetMinutes),
)

private fun AgendaPlanStageEditorState.toDraft(): AgendaPlanStageDraft {
    val scheduledValue = scheduled.toTiming()
    val dueValue = due.toTiming()
    val triggerValue = trigger.toTiming()
    val reminderValue = reminder.toTiming()
    return AgendaPlanStageDraft(
        title = title.trim(),
        note = note.trim(),
        scheduledAt = scheduledValue.absoluteAt,
        dueAt = dueValue.absoluteAt,
        triggerAt = triggerValue.absoluteAt,
        reminderAt = reminderValue.absoluteAt,
        scheduledOffsetMinutes = scheduledValue.offsetMinutes,
        dueOffsetMinutes = dueValue.offsetMinutes,
        triggerOffsetMinutes = triggerValue.offsetMinutes,
        reminderOffsetMinutes = reminderValue.offsetMinutes,
    )
}

private data class AgendaPlanTiming(
    val absoluteAt: Long?,
    val offsetMinutes: Long?,
)

private fun AgendaPlanTimeEditorValue.toTiming(): AgendaPlanTiming = when (mode) {
    AgendaPlanTimeMode.NONE -> AgendaPlanTiming(absoluteAt = null, offsetMinutes = null)
    AgendaPlanTimeMode.ABSOLUTE -> AgendaPlanTiming(
        absoluteAt = requireNotNull(absoluteAt),
        offsetMinutes = null,
    )
    AgendaPlanTimeMode.RELATIVE -> AgendaPlanTiming(
        absoluteAt = null,
        offsetMinutes = requireNotNull(offsetMinutes.trim().toLongOrNull()),
    )
}

private fun AgendaPlanStageTimeField?.label(): String = when (this) {
    AgendaPlanStageTimeField.SCHEDULED -> "计划时间"
    AgendaPlanStageTimeField.DUE -> "截止时间"
    AgendaPlanStageTimeField.TRIGGER -> "触发时间"
    AgendaPlanStageTimeField.REMINDER -> "提醒时间"
    null -> "时间"
}
