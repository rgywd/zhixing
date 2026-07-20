package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "work_sessions",
    indices = [
        Index(value = ["machine_id"]),
        Index(value = ["updated_at"]),
    ],
)
data class WorkSessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("machine_id")
    val machineId: String?,
    @ColumnInfo("path")
    val path: String?,
    @ColumnInfo("host")
    val host: String?,
    @ColumnInfo("name")
    val name: String?,
    @ColumnInfo("agent")
    val agent: String,
    @ColumnInfo("active")
    val active: Boolean,
    @ColumnInfo("active_at")
    val activeAt: Long,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("approvals", defaultValue = "[]")
    val approvals: String = "[]",
    @ColumnInfo("decryptable", defaultValue = "1")
    val decryptable: Boolean = true,
    @ColumnInfo("last_permission_mode")
    val lastPermissionMode: String? = null,
)
