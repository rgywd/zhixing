package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
)
