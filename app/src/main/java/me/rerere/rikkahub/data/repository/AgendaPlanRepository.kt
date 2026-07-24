package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.agenda.AgendaPlanReminderGateway
import me.rerere.rikkahub.data.agenda.currentAgendaPlanStage
import me.rerere.rikkahub.data.agenda.resolveAgendaRelativeTime
import me.rerere.rikkahub.data.db.dao.AgendaPlanDAO
import me.rerere.rikkahub.data.db.entity.toAgendaPlanStage
import me.rerere.rikkahub.data.db.entity.toAgendaPlanWithStages
import me.rerere.rikkahub.data.db.entity.toEntity
import me.rerere.rikkahub.data.model.AgendaPlan
import me.rerere.rikkahub.data.model.AgendaPlanSource
import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStageDraft
import me.rerere.rikkahub.data.model.AgendaPlanStageStatus
import me.rerere.rikkahub.data.model.AgendaPlanStatus
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
import java.util.UUID

class AgendaPlanRepository(
    private val dao: AgendaPlanDAO,
    private val reminderGateway: AgendaPlanReminderGateway,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observeVisiblePlans(): Flow<List<AgendaPlanWithStages>> = dao.observeVisiblePlans().map { rows ->
        rows.map { it.toAgendaPlanWithStages() }
    }

    suspend fun getVisiblePlans(): List<AgendaPlanWithStages> =
        dao.getVisiblePlans().map { it.toAgendaPlanWithStages() }

    suspend fun getById(id: String): AgendaPlanWithStages? = dao.getById(id)?.toAgendaPlanWithStages()

    suspend fun getStageById(id: String): AgendaPlanStage? = dao.getStageById(id)?.toAgendaPlanStage()

    suspend fun create(
        title: String,
        note: String = "",
        location: String = "",
        eventAt: Long?,
        stages: List<AgendaPlanStageDraft>,
        source: AgendaPlanSource = AgendaPlanSource.MANUAL,
        sourceReference: String? = null,
        conversationId: String? = null,
    ): AgendaPlanWithStages {
        require(title.isNotBlank()) { "长期事项标题不能为空" }
        require(stages.isNotEmpty()) { "长期事项至少需要一个阶段" }
        stages.forEach(::validateDraft)
        val timestamp = now()
        val planId = UUID.randomUUID().toString()
        val plan = AgendaPlan(
            id = planId,
            title = title.trim(),
            note = note.trim(),
            location = location.trim(),
            status = AgendaPlanStatus.ACTIVE,
            eventAt = eventAt,
            source = source,
            sourceReference = sourceReference?.trim()?.takeIf { it.isNotEmpty() },
            conversationId = conversationId,
            createdAt = timestamp,
            updatedAt = timestamp,
            completedAt = null,
        )
        val planStages = stages.mapIndexed { index, draft ->
            draft.toStage(
                id = UUID.randomUUID().toString(),
                planId = planId,
                position = index,
                eventAt = eventAt,
                timestamp = timestamp,
            )
        }
        val bundle = AgendaPlanWithStages(plan, planStages)
        dao.upsertPlanWithStages(plan.toEntity(), planStages.map { it.toEntity() })
        syncPlan(bundle)
        return bundle
    }

    suspend fun updatePlan(
        id: String,
        title: String,
        note: String,
        location: String,
        eventAt: Long?,
        sourceReference: String?,
    ): AgendaPlanWithStages {
        require(title.isNotBlank()) { "长期事项标题不能为空" }
        val old = getById(id) ?: error("长期事项不存在")
        val timestamp = now()
        if (eventAt == null) {
            require(old.stages.none { it.hasRelativeTiming() }) {
                "存在相对阶段时不能清除计划发生时间"
            }
        }
        val updatedPlan = old.plan.copy(
            title = title.trim(),
            note = note.trim(),
            location = location.trim(),
            eventAt = eventAt,
            sourceReference = sourceReference?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = timestamp,
        )
        val updatedStages = old.stages.map { stage ->
            stage.copy(
                scheduledAt = resolveAgendaRelativeTime(eventAt, stage.scheduledAt, stage.scheduledOffsetMinutes),
                dueAt = resolveAgendaRelativeTime(eventAt, stage.dueAt, stage.dueOffsetMinutes),
                triggerAt = resolveAgendaRelativeTime(eventAt, stage.triggerAt, stage.triggerOffsetMinutes),
                reminderAt = resolveAgendaRelativeTime(eventAt, stage.reminderAt, stage.reminderOffsetMinutes),
                updatedAt = timestamp,
            )
        }
        val updated = AgendaPlanWithStages(updatedPlan, updatedStages)
        dao.upsertPlanWithStages(updatedPlan.toEntity(), updatedStages.map { it.toEntity() })
        syncPlan(updated)
        return updated
    }

    /**
     * Atomically updates a plan and every existing stage in position order.
     *
     * Stage topology is intentionally stable: callers may edit stage content and timing, but
     * cannot add, remove, or reorder stages through this operation.
     */
    suspend fun updatePlanWithStages(
        id: String,
        title: String,
        note: String,
        location: String,
        eventAt: Long?,
        sourceReference: String?,
        stages: List<AgendaPlanStageDraft>,
    ): AgendaPlanWithStages {
        require(title.isNotBlank()) { "长期事项标题不能为空" }
        val old = getById(id) ?: error("长期事项不存在")
        val orderedOldStages = old.stages.sortedBy(AgendaPlanStage::position)
        require(stages.size == orderedOldStages.size) { "编辑长期事项时不能增删阶段" }
        stages.forEach(::validateDraft)

        val timestamp = now()
        val updatedPlan = old.plan.copy(
            title = title.trim(),
            note = note.trim(),
            location = location.trim(),
            eventAt = eventAt,
            sourceReference = sourceReference?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = timestamp,
        )
        val updatedStages = stages.mapIndexed { index, draft ->
            val oldStage = orderedOldStages[index]
            draft.toStage(
                id = oldStage.id,
                planId = oldStage.planId,
                position = oldStage.position,
                eventAt = eventAt,
                timestamp = timestamp,
                createdAt = oldStage.createdAt,
                status = oldStage.status,
                completedAt = oldStage.completedAt,
            )
        }
        val updated = AgendaPlanWithStages(updatedPlan, updatedStages)
        dao.upsertPlanWithStages(updatedPlan.toEntity(), updatedStages.map { it.toEntity() })
        syncPlan(updated)
        return updated
    }

    suspend fun updateStage(
        stageId: String,
        draft: AgendaPlanStageDraft,
    ): AgendaPlanWithStages {
        validateDraft(draft)
        val oldStage = dao.getStageById(stageId)?.toAgendaPlanStage() ?: error("阶段不存在")
        val old = getById(oldStage.planId) ?: error("长期事项不存在")
        val timestamp = now()
        val updatedStage = draft.toStage(
            id = oldStage.id,
            planId = oldStage.planId,
            position = oldStage.position,
            eventAt = old.plan.eventAt,
            timestamp = timestamp,
            createdAt = oldStage.createdAt,
            status = oldStage.status,
            completedAt = oldStage.completedAt,
        )
        val stages = old.stages.map { if (it.id == stageId) updatedStage else it }
        val updatedPlan = old.plan.copy(updatedAt = timestamp)
        val updated = AgendaPlanWithStages(updatedPlan, stages)
        dao.upsertPlanWithStages(updatedPlan.toEntity(), stages.map { it.toEntity() })
        syncPlan(updated)
        return updated
    }

    suspend fun setStageCompleted(stageId: String, completed: Boolean): AgendaPlanWithStages {
        val oldStage = dao.getStageById(stageId)?.toAgendaPlanStage() ?: error("阶段不存在")
        val old = getById(oldStage.planId) ?: error("长期事项不存在")
        val timestamp = now()
        val updatedStage = oldStage.copy(
            status = if (completed) AgendaPlanStageStatus.COMPLETED else AgendaPlanStageStatus.PENDING,
            completedAt = if (completed) timestamp else null,
            updatedAt = timestamp,
        )
        val stages = old.stages.map { if (it.id == stageId) updatedStage else it }
        val updatedPlan = old.plan.copy(
            status = if (!completed) AgendaPlanStatus.ACTIVE else old.plan.status,
            completedAt = if (!completed) null else old.plan.completedAt,
            updatedAt = timestamp,
        )
        val updated = AgendaPlanWithStages(updatedPlan, stages)
        dao.upsertPlanWithStages(updatedPlan.toEntity(), stages.map { it.toEntity() })
        syncPlan(updated)
        return updated
    }

    suspend fun setPlanStatus(id: String, status: AgendaPlanStatus): AgendaPlanWithStages {
        val old = getById(id) ?: error("长期事项不存在")
        val timestamp = now()
        val updated = old.copy(
            plan = old.plan.copy(
                status = status,
                completedAt = if (status == AgendaPlanStatus.COMPLETED) timestamp else null,
                updatedAt = timestamp,
            )
        )
        dao.upsertPlan(updated.plan.toEntity())
        syncPlan(updated)
        return updated
    }

    suspend fun reminderPayload(
        stageId: String,
        expectedReminderAt: Long? = null,
    ): AgendaPlanReminderPayload? {
        val stage = dao.getStageById(stageId)?.toAgendaPlanStage() ?: return null
        val plan = getById(stage.planId) ?: return null
        if (plan.plan.status != AgendaPlanStatus.ACTIVE || stage.status != AgendaPlanStageStatus.PENDING) return null
        if (currentAgendaPlanStage(plan)?.id != stage.id) return null
        if (expectedReminderAt != null && stage.reminderAt != expectedReminderAt) return null
        return AgendaPlanReminderPayload(
            stageId = stage.id,
            planId = plan.plan.id,
            title = stage.title,
            note = stage.note.ifBlank { plan.plan.title },
        )
    }

    suspend fun reconcileReminders() {
        getVisiblePlans().forEach(::syncPlan)
    }

    private fun syncPlan(plan: AgendaPlanWithStages) {
        plan.stages.forEach { reminderGateway.cancel(it.id) }
        if (plan.plan.status != AgendaPlanStatus.ACTIVE) return
        currentAgendaPlanStage(plan)?.let(reminderGateway::sync)
    }

    private fun validateDraft(draft: AgendaPlanStageDraft) {
        require(draft.title.isNotBlank()) { "阶段标题不能为空" }
        validateTimePair(draft.scheduledAt, draft.scheduledOffsetMinutes, "计划时间")
        validateTimePair(draft.dueAt, draft.dueOffsetMinutes, "截止时间")
        validateTimePair(draft.triggerAt, draft.triggerOffsetMinutes, "触发时间")
        validateTimePair(draft.reminderAt, draft.reminderOffsetMinutes, "提醒时间")
    }

    private fun validateTimePair(absoluteAt: Long?, offsetMinutes: Long?, label: String) {
        require(absoluteAt == null || offsetMinutes == null) { "$label 不能同时使用绝对时间和相对时间" }
    }

    private fun AgendaPlanStageDraft.toStage(
        id: String,
        planId: String,
        position: Int,
        eventAt: Long?,
        timestamp: Long,
        createdAt: Long = timestamp,
        status: AgendaPlanStageStatus = AgendaPlanStageStatus.PENDING,
        completedAt: Long? = null,
    ) = AgendaPlanStage(
        id = id,
        planId = planId,
        title = title.trim(),
        note = note.trim(),
        status = status,
        position = position,
        scheduledAt = resolveAgendaRelativeTime(eventAt, scheduledAt, scheduledOffsetMinutes),
        dueAt = resolveAgendaRelativeTime(eventAt, dueAt, dueOffsetMinutes),
        triggerAt = resolveAgendaRelativeTime(eventAt, triggerAt, triggerOffsetMinutes),
        reminderAt = resolveAgendaRelativeTime(eventAt, reminderAt, reminderOffsetMinutes),
        scheduledOffsetMinutes = scheduledOffsetMinutes,
        dueOffsetMinutes = dueOffsetMinutes,
        triggerOffsetMinutes = triggerOffsetMinutes,
        reminderOffsetMinutes = reminderOffsetMinutes,
        createdAt = createdAt,
        updatedAt = timestamp,
        completedAt = completedAt,
    )

    private fun AgendaPlanStage.hasRelativeTiming(): Boolean =
        scheduledOffsetMinutes != null ||
            dueOffsetMinutes != null ||
            triggerOffsetMinutes != null ||
            reminderOffsetMinutes != null
}

data class AgendaPlanReminderPayload(
    val stageId: String,
    val planId: String,
    val title: String,
    val note: String,
)
