package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.workflow.WorkAgent
import me.rerere.rikkahub.data.workflow.WorkApproval
import me.rerere.rikkahub.data.workflow.WorkSession
import me.rerere.rikkahub.utils.JsonInstant

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
) {
    fun toModel(): WorkSession = WorkSession(
        id = id,
        machineId = machineId,
        path = path,
        host = host,
        name = name,
        agent = WorkAgent.fromName(agent),
        active = active,
        activeAt = activeAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        approvals = runCatching {
            JsonInstant.decodeFromString<List<WorkApproval>>(approvals)
        }.getOrDefault(emptyList()),
        decryptable = decryptable,
        lastPermissionMode = lastPermissionMode,
    )

    companion object {
        fun fromModel(session: WorkSession): WorkSessionEntity = WorkSessionEntity(
            id = session.id,
            machineId = session.machineId,
            path = session.path,
            host = session.host,
            name = session.name,
            agent = session.agent.name,
            active = session.active,
            activeAt = session.activeAt,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            approvals = JsonInstant.encodeToString(session.approvals),
            decryptable = session.decryptable,
            lastPermissionMode = session.lastPermissionMode,
        )
    }
}
