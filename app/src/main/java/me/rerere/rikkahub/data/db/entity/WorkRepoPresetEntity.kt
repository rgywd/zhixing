package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.workflow.RepoPreset
import me.rerere.rikkahub.data.workflow.WorkAgent
import me.rerere.rikkahub.utils.JsonInstant

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
) {
    fun toModel(): RepoPreset = RepoPreset(
        id = id,
        name = name,
        machineId = machineId,
        path = path,
        defaultBranch = defaultBranch,
        agent = WorkAgent.fromName(agent),
        model = model,
        reasoningEffort = reasoningEffort,
        fullAccess = fullAccess,
        disallowedTools = runCatching {
            JsonInstant.decodeFromString<List<String>>(disallowedTools)
        }.getOrDefault(emptyList()),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromModel(preset: RepoPreset): WorkRepoPresetEntity = WorkRepoPresetEntity(
            id = preset.id,
            name = preset.name,
            machineId = preset.machineId,
            path = preset.path,
            defaultBranch = preset.defaultBranch,
            agent = preset.agent.name,
            model = preset.model,
            reasoningEffort = preset.reasoningEffort,
            fullAccess = preset.fullAccess,
            disallowedTools = JsonInstant.encodeToString(preset.disallowedTools),
            createdAt = preset.createdAt,
            updatedAt = preset.updatedAt,
        )
    }
}
