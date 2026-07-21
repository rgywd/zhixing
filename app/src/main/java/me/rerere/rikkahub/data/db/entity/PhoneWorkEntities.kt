package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "phone_work_sessions", primaryKeys = ["id"])
data class PhoneWorkSessionEntity(
    val id: String,
    @ColumnInfo(name = "runner_id") val runnerId: String,
    @ColumnInfo(name = "repo_id") val repoId: String,
    @ColumnInfo(name = "repo_name") val repoName: String,
    @ColumnInfo(defaultValue = "''") val title: String,
    val model: String,
    @ColumnInfo(name = "reasoning_effort") val reasoningEffort: String,
    val status: String,
    @ColumnInfo(name = "codex_session_id") val codexSessionId: String?,
    @ColumnInfo(name = "last_seq") val lastSeq: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(tableName = "phone_work_events", primaryKeys = ["session_id", "seq"])
data class PhoneWorkEventEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val seq: Long,
    val id: String,
    val type: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
)
