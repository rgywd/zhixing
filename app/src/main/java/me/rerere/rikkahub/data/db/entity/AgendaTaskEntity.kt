package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskSource
import me.rerere.rikkahub.data.model.AgendaTaskStatus

@Entity(
    tableName = "agenda_tasks",
    indices = [
        Index(value = ["status", "due_at"]),
        Index(value = ["reminder_at"]),
    ],
)
data class AgendaTaskEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val note: String = "",
    val status: String = AgendaTaskStatus.PENDING.name,
    @ColumnInfo("due_at")
    val dueAt: Long? = null,
    @ColumnInfo("reminder_at")
    val reminderAt: Long? = null,
    val source: String = AgendaTaskSource.MANUAL.name,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("completed_at")
    val completedAt: Long? = null,
)

fun AgendaTaskEntity.toAgendaTask(): AgendaTask = AgendaTask(
    id = id,
    title = title,
    note = note,
    status = runCatching { AgendaTaskStatus.valueOf(status) }.getOrDefault(AgendaTaskStatus.PENDING),
    dueAt = dueAt,
    reminderAt = reminderAt,
    source = runCatching { AgendaTaskSource.valueOf(source) }.getOrDefault(AgendaTaskSource.MANUAL),
    conversationId = conversationId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
)

fun AgendaTask.toEntity(): AgendaTaskEntity = AgendaTaskEntity(
    id = id,
    title = title,
    note = note,
    status = status.name,
    dueAt = dueAt,
    reminderAt = reminderAt,
    source = source.name,
    conversationId = conversationId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
)
