package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "work_machines")
data class WorkMachineEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("host")
    val host: String,
    @ColumnInfo("display_name")
    val displayName: String?,
    @ColumnInfo("platform")
    val platform: String?,
    @ColumnInfo("active")
    val active: Boolean,
    @ColumnInfo("active_at")
    val activeAt: Long,
    @ColumnInfo("supports_codex")
    val supportsCodex: Boolean?,
    @ColumnInfo("supports_claude")
    val supportsClaude: Boolean?,
    @ColumnInfo("home_dir")
    val homeDir: String?,
)
