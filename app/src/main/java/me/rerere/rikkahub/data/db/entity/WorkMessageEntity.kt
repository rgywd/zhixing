package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.workflow.WorkMessage
import me.rerere.rikkahub.data.workflow.WorkMessagePart
import me.rerere.rikkahub.data.workflow.WorkRole
import me.rerere.rikkahub.utils.JsonInstant

@Entity(
    tableName = "work_messages",
    indices = [
        Index(value = ["session_id", "seq"]),
    ],
)
data class WorkMessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("session_id")
    val sessionId: String,
    @ColumnInfo("seq")
    val seq: Long,
    @ColumnInfo("role")
    val role: String,
    @ColumnInfo("parts")
    val parts: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
) {
    fun toModel(): WorkMessage = WorkMessage(
        id = id,
        sessionId = sessionId,
        seq = seq,
        role = if (role == WorkRole.USER.name) WorkRole.USER else WorkRole.AGENT,
        parts = runCatching {
            JsonInstant.decodeFromString<List<WorkMessagePart>>(parts)
        }.getOrDefault(emptyList()),
        createdAt = createdAt,
    )

    companion object {
        fun fromModel(message: WorkMessage): WorkMessageEntity = WorkMessageEntity(
            id = message.id,
            sessionId = message.sessionId,
            seq = message.seq,
            role = message.role.name,
            parts = JsonInstant.encodeToString(message.parts),
            createdAt = message.createdAt,
        )
    }
}
