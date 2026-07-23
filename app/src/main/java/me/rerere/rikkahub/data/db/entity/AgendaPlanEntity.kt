package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import me.rerere.rikkahub.data.model.AgendaPlan
import me.rerere.rikkahub.data.model.AgendaPlanSource
import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStageStatus
import me.rerere.rikkahub.data.model.AgendaPlanStatus
import me.rerere.rikkahub.data.model.AgendaPlanWithStages

@Entity(
    tableName = "agenda_plans",
    indices = [
        Index(value = ["status", "event_at"]),
        Index(value = ["updated_at"]),
    ],
)
data class AgendaPlanEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val note: String = "",
    val location: String = "",
    val status: String = AgendaPlanStatus.ACTIVE.name,
    @ColumnInfo("event_at")
    val eventAt: Long? = null,
    val source: String = AgendaPlanSource.MANUAL.name,
    @ColumnInfo("source_reference")
    val sourceReference: String? = null,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("completed_at")
    val completedAt: Long? = null,
)

@Entity(
    tableName = "agenda_plan_stages",
    foreignKeys = [
        ForeignKey(
            entity = AgendaPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["plan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["plan_id", "position"]),
        Index(value = ["status", "trigger_at", "due_at"]),
        Index(value = ["reminder_at"]),
    ],
)
data class AgendaPlanStageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("plan_id")
    val planId: String,
    val title: String,
    val note: String = "",
    val status: String = AgendaPlanStageStatus.PENDING.name,
    val position: Int,
    @ColumnInfo("scheduled_at")
    val scheduledAt: Long? = null,
    @ColumnInfo("due_at")
    val dueAt: Long? = null,
    @ColumnInfo("trigger_at")
    val triggerAt: Long? = null,
    @ColumnInfo("reminder_at")
    val reminderAt: Long? = null,
    @ColumnInfo("scheduled_offset_minutes")
    val scheduledOffsetMinutes: Long? = null,
    @ColumnInfo("due_offset_minutes")
    val dueOffsetMinutes: Long? = null,
    @ColumnInfo("trigger_offset_minutes")
    val triggerOffsetMinutes: Long? = null,
    @ColumnInfo("reminder_offset_minutes")
    val reminderOffsetMinutes: Long? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("completed_at")
    val completedAt: Long? = null,
)

data class AgendaPlanWithStagesEntity(
    @Embedded
    val plan: AgendaPlanEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "plan_id",
    )
    val stages: List<AgendaPlanStageEntity>,
)

fun AgendaPlanEntity.toAgendaPlan(): AgendaPlan = AgendaPlan(
    id = id,
    title = title,
    note = note,
    location = location,
    status = runCatching { AgendaPlanStatus.valueOf(status) }.getOrDefault(AgendaPlanStatus.ACTIVE),
    eventAt = eventAt,
    source = runCatching { AgendaPlanSource.valueOf(source) }.getOrDefault(AgendaPlanSource.MANUAL),
    sourceReference = sourceReference,
    conversationId = conversationId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
)
fun AgendaPlanStageEntity.toAgendaPlanStage(): AgendaPlanStage = AgendaPlanStage(
    id = id,
    planId = planId,
    title = title,
    note = note,
    status = runCatching { AgendaPlanStageStatus.valueOf(status) }.getOrDefault(AgendaPlanStageStatus.PENDING),
    position = position,
    scheduledAt = scheduledAt,
    dueAt = dueAt,
    triggerAt = triggerAt,
    reminderAt = reminderAt,
    scheduledOffsetMinutes = scheduledOffsetMinutes,
    dueOffsetMinutes = dueOffsetMinutes,
    triggerOffsetMinutes = triggerOffsetMinutes,
    reminderOffsetMinutes = reminderOffsetMinutes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
)

fun AgendaPlanWithStagesEntity.toAgendaPlanWithStages(): AgendaPlanWithStages = AgendaPlanWithStages(
    plan = plan.toAgendaPlan(),
    stages = stages.map { it.toAgendaPlanStage() }.sortedBy { it.position },
)

fun AgendaPlan.toEntity(): AgendaPlanEntity = AgendaPlanEntity(
    id = id,
    title = title,
    note = note,
    location = location,
    status = status.name,
    eventAt = eventAt,
    source = source.name,
    sourceReference = sourceReference,
    conversationId = conversationId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
)

fun AgendaPlanStage.toEntity(): AgendaPlanStageEntity = AgendaPlanStageEntity(
    id = id,
    planId = planId,
    title = title,
    note = note,
    status = status.name,
    position = position,
    scheduledAt = scheduledAt,
    dueAt = dueAt,
    triggerAt = triggerAt,
    reminderAt = reminderAt,
    scheduledOffsetMinutes = scheduledOffsetMinutes,
    dueOffsetMinutes = dueOffsetMinutes,
    triggerOffsetMinutes = triggerOffsetMinutes,
    reminderOffsetMinutes = reminderOffsetMinutes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
)
