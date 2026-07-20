package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "work_repo_presets",
    indices = [
        Index(value = ["machine_id", "path"], unique = true),
    ],
)
data class WorkRepoPresetEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("machine_id")
    val machineId: String,
    @ColumnInfo("path")
    val path: String,
    @ColumnInfo("default_branch")
    val defaultBranch: String?,
    @ColumnInfo("agent")
    val agent: String,
    @ColumnInfo("model")
    val model: String?,
    @ColumnInfo("reasoning_effort")
    val reasoningEffort: String?,
    @ColumnInfo("full_access", defaultValue = "0")
    val fullAccess: Boolean = false,
    @ColumnInfo("disallowed_tools", defaultValue = "[]")
    val disallowedTools: String = "[]",
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
