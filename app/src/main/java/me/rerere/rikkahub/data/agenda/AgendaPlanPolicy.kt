package me.rerere.rikkahub.data.agenda

import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStageStatus
import me.rerere.rikkahub.data.model.AgendaPlanStatus
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import java.time.Instant
import java.time.ZoneId

enum class AgendaPlanPhase {
    NEEDS_ACTION,
    UPCOMING,
    WAITING,
    COMPLETED,
}

sealed interface AgendaAction {
    val stableId: String
    val title: String
    val context: String?
    val actionAt: Long?
    val dueAt: Long?

    data class Task(
        val task: AgendaTask,
    ) : AgendaAction {
        override val stableId: String = "task:${task.id}"
        override val title: String = task.title
        override val context: String? = null
        override val actionAt: Long? = task.dueAt
        override val dueAt: Long? = task.dueAt
    }

    data class PlanStage(
        val planWithStages: AgendaPlanWithStages,
        val stage: AgendaPlanStage,
    ) : AgendaAction {
        override val stableId: String = "stage:${stage.id}"
        override val title: String = stage.title
        override val context: String = planWithStages.plan.title
        override val actionAt: Long? = agendaStageActionAt(stage)
        override val dueAt: Long? = stage.dueAt
    }
}

data class AgendaPlanProjection(
    val value: AgendaPlanWithStages,
    val currentStage: AgendaPlanStage?,
    val phase: AgendaPlanPhase,
    val nextAt: Long?,
)

data class AgendaProjection(
    val actions: List<AgendaAction>,
    val inboxTasks: List<AgendaTask>,
    val futureTasks: List<AgendaTask>,
    val upcomingPlans: List<AgendaPlanProjection>,
    val waitingPlans: List<AgendaPlanProjection>,
    val completedTasks: List<AgendaTask>,
    val completedPlans: List<AgendaPlanProjection>,
) {
    val upcomingPlanCount: Int
        get() = upcomingPlans.size + waitingPlans.size
}

data class AgendaSummary(
    val actionCount: Int,
    val inboxCount: Int,
    val upcomingPlanCount: Int,
    val nextActionAt: Long?,
    val nextPlanAt: Long?,
)

fun AgendaProjection.toSummary(): AgendaSummary = AgendaSummary(
    actionCount = actions.size,
    inboxCount = inboxTasks.size,
    upcomingPlanCount = upcomingPlanCount,
    nextActionAt = actions.mapNotNull { it.actionAt }.minOrNull(),
    nextPlanAt = (upcomingPlans + waitingPlans).mapNotNull { it.nextAt }.minOrNull(),
)

fun currentAgendaPlanStage(plan: AgendaPlanWithStages): AgendaPlanStage? =
    plan.stages
        .asSequence()
        .filter { it.status == AgendaPlanStageStatus.PENDING }
        .minByOrNull { it.position }

fun agendaStageActionAt(stage: AgendaPlanStage): Long? =
    listOfNotNull(stage.triggerAt, stage.scheduledAt, stage.dueAt, stage.reminderAt).minOrNull()

fun buildAgendaProjection(
    tasks: List<AgendaTask>,
    plans: List<AgendaPlanWithStages>,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): AgendaProjection {
    val tomorrowStart = Instant.ofEpochMilli(nowMillis)
        .atZone(zoneId)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

    val pendingTasks = tasks.filter { it.status == AgendaTaskStatus.PENDING }
    val taskActions = pendingTasks
        .filter { it.dueAt != null && it.dueAt < tomorrowStart }
        .map(AgendaAction::Task)
    val inboxTasks = pendingTasks
        .filter { it.dueAt == null }
        .sortedByDescending(AgendaTask::updatedAt)
    val futureTasks = pendingTasks
        .filter { it.dueAt != null && it.dueAt >= tomorrowStart }
        .sortedBy { it.dueAt }

    val projectedPlans = plans.map { value ->
        val current = currentAgendaPlanStage(value)
        val nextAt = if (current == null) value.plan.eventAt else agendaStageActionAt(current)
        val phase = when {
            value.plan.status == AgendaPlanStatus.COMPLETED -> AgendaPlanPhase.COMPLETED
            value.plan.status != AgendaPlanStatus.ACTIVE -> AgendaPlanPhase.COMPLETED
            current == null && value.stages.any { it.status == AgendaPlanStageStatus.COMPLETED } ->
                AgendaPlanPhase.WAITING
            current == null -> AgendaPlanPhase.UPCOMING
            nextAt == null || nextAt <= nowMillis -> AgendaPlanPhase.NEEDS_ACTION
            value.stages.any { it.status == AgendaPlanStageStatus.COMPLETED } -> AgendaPlanPhase.WAITING
            else -> AgendaPlanPhase.UPCOMING
        }
        AgendaPlanProjection(value, current, phase, nextAt)
    }

    val stageActions = projectedPlans
        .filter { it.phase == AgendaPlanPhase.NEEDS_ACTION }
        .mapNotNull { projection ->
            projection.currentStage?.let { AgendaAction.PlanStage(projection.value, it) }
        }
    val actions = (taskActions + stageActions).sortedWith(
        compareBy<AgendaAction>(
            { actionUrgencyRank(it, nowMillis) },
            { it.dueAt ?: Long.MAX_VALUE },
            { it.actionAt ?: Long.MAX_VALUE },
            { it.stableId },
        )
    )

    return AgendaProjection(
        actions = actions,
        inboxTasks = inboxTasks,
        futureTasks = futureTasks,
        upcomingPlans = projectedPlans
            .filter { it.phase == AgendaPlanPhase.UPCOMING }
            .sortedWith(compareBy({ it.nextAt ?: Long.MAX_VALUE }, { it.value.plan.eventAt ?: Long.MAX_VALUE })),
        waitingPlans = projectedPlans
            .filter { it.phase == AgendaPlanPhase.WAITING }
            .sortedWith(compareBy({ it.nextAt ?: Long.MAX_VALUE }, { it.value.plan.eventAt ?: Long.MAX_VALUE })),
        completedTasks = tasks
            .filter { it.status == AgendaTaskStatus.COMPLETED }
            .sortedByDescending { it.completedAt ?: it.updatedAt },
        completedPlans = projectedPlans
            .filter { it.phase == AgendaPlanPhase.COMPLETED }
            .sortedByDescending { it.value.plan.completedAt ?: it.value.plan.updatedAt },
    )
}

private fun actionUrgencyRank(action: AgendaAction, nowMillis: Long): Int = when {
    action.dueAt != null && action.dueAt!! < nowMillis -> 0
    action.actionAt != null && action.actionAt!! <= nowMillis -> 1
    action.actionAt == null -> 2
    else -> 3
}

fun resolveAgendaRelativeTime(
    eventAt: Long?,
    absoluteAt: Long?,
    offsetMinutes: Long?,
): Long? {
    if (offsetMinutes == null) return absoluteAt
    requireNotNull(eventAt) { "使用相对时间的阶段需要设置计划发生时间" }
    val offsetMillis = Math.multiplyExact(offsetMinutes, 60_000L)
    return Math.addExact(eventAt, offsetMillis)
}
