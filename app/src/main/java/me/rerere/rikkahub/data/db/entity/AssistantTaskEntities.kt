package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "assistant_tasks",
    indices = [
        Index(value = ["conversation_id", "status"]),
        Index(value = ["updated_at"]),
    ],
)
data class AssistantTaskEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val status: String,
    val attempt: Int,
    @ColumnInfo(name = "conversation_id") val conversationId: String?,
    @ColumnInfo(name = "anchor_message_id") val anchorMessageId: String?,
    @ColumnInfo(name = "anchor_node_id") val anchorNodeId: String?,
    val summary: String?,
    @ColumnInfo(name = "result_kind") val resultKind: String?,
    @ColumnInfo(name = "result_ref") val resultRef: String?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "attention_reason") val attentionReason: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
)

@Entity(
    tableName = "assistant_task_events",
    primaryKeys = ["task_id", "seq"],
    indices = [
        Index(value = ["task_id", "idempotency_key"], unique = true),
        Index(value = ["created_at"]),
    ],
)
data class AssistantTaskEventEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    val seq: Long,
    val type: String,
    val message: String,
    @ColumnInfo(name = "result_ref") val resultRef: String?,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "assistant_task_links",
    primaryKeys = ["task_id", "object_type", "object_id", "role"],
    indices = [Index(value = ["object_type", "object_id"])],
)
data class AssistantTaskLinkEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "object_type") val objectType: String,
    @ColumnInfo(name = "object_id") val objectId: String,
    val role: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "assistant_runtime_contexts",
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["message_id"], unique = true),
    ],
)
data class AssistantRuntimeContextEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    val kind: String,
    val title: String,
    val summary: String,
    val recommendation: String?,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    @ColumnInfo(name = "valid_until") val validUntil: Long,
    @ColumnInfo(name = "privacy_scope_json") val privacyScopeJson: String,
    @ColumnInfo(name = "evidence_json") val evidenceJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
