package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "codex_machines", primaryKeys = ["machine_id"])
data class CodexMachineEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "platform_family") val platformFamily: String,
    @ColumnInfo(name = "platform_os") val platformOs: String,
    @ColumnInfo(name = "agent_version") val agentVersion: String,
    @ColumnInfo(name = "codex_version") val codexVersion: String?,
    @ColumnInfo(name = "schema_hash") val schemaHash: String?,
    @ColumnInfo(name = "runtime_writable") val runtimeWritable: Boolean,
    @ColumnInfo(name = "compatibility_reason") val compatibilityReason: String?,
    @ColumnInfo(name = "operations_json") val operationsJson: String,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
)

@Entity(
    tableName = "codex_projects",
    primaryKeys = ["project_id"],
    indices = [Index("machine_id"), Index("updated_at")],
)
data class CodexProjectEntity(
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "canonical_root") val canonicalRoot: String,
    @ColumnInfo(name = "exists_on_disk", defaultValue = "1") val existsOnDisk: Boolean,
    @ColumnInfo(name = "vcs_kind") val vcsKind: String,
    @ColumnInfo(name = "vcs_origin_url") val vcsOriginUrl: String?,
    @ColumnInfo(name = "vcs_branch") val vcsBranch: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "codex_project_preferences", primaryKeys = ["project_id"])
data class CodexProjectPreferenceEntity(
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    @ColumnInfo(name = "is_hidden") val isHidden: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "codex_threads",
    primaryKeys = ["machine_id", "thread_id"],
    indices = [Index("project_id"), Index("recency_at"), Index("parent_thread_id")],
)
data class CodexThreadEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "preview") val preview: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "recency_at") val recencyAt: Long,
    @ColumnInfo(name = "archived") val archived: Boolean,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "thread_source") val threadSource: String?,
    @ColumnInfo(name = "parent_thread_id") val parentThreadId: String?,
    @ColumnInfo(name = "forked_from_id") val forkedFromId: String?,
    @ColumnInfo(name = "is_subagent") val isSubagent: Boolean,
    @ColumnInfo(name = "is_automation") val isAutomation: Boolean,
    @ColumnInfo(name = "runtime_state") val runtimeState: String,
    @ColumnInfo(name = "raw_status") val rawStatus: String,
)

@Entity(tableName = "codex_thread_preferences", primaryKeys = ["machine_id", "thread_id"])
data class CodexThreadPreferenceEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "codex_turns",
    primaryKeys = ["machine_id", "thread_id", "turn_id"],
    indices = [Index(value = ["machine_id", "thread_id"])],
)
data class CodexTurnEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "turn_id") val turnId: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "error") val error: String?,
    @ColumnInfo(name = "position") val position: Int,
)

@Entity(
    tableName = "codex_items",
    primaryKeys = ["machine_id", "thread_id", "turn_id", "item_id"],
    indices = [Index(value = ["machine_id", "thread_id"]), Index(value = ["machine_id", "thread_id", "turn_id"])],
)
data class CodexItemEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "turn_id") val turnId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "raw_type") val rawType: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "text") val text: String?,
    @ColumnInfo(name = "status") val status: String?,
    @ColumnInfo(name = "raw_json") val rawJson: String,
    @ColumnInfo(name = "position") val position: Int,
)

@Entity(
    tableName = "codex_runtime_bindings",
    primaryKeys = ["machine_id", "thread_id"],
)
data class CodexRuntimeBindingEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "binding_id") val bindingId: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "codex_approvals",
    primaryKeys = ["machine_id", "thread_id", "approval_id"],
    indices = [Index(value = ["machine_id", "thread_id"])],
)
data class CodexApprovalEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "approval_id") val approvalId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "codex_catalog_sync", primaryKeys = ["machine_id"])
data class CodexCatalogSyncEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
)

@Entity(
    tableName = "codex_catalog_chunks",
    primaryKeys = ["snapshot_id", "chunk_index"],
    indices = [Index("received_at")],
)
data class CodexCatalogChunkEntity(
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "chunk_count") val chunkCount: Int,
    @ColumnInfo(name = "revision") val revision: Long,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    @ColumnInfo(name = "machine_json") val machineJson: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "chunk_hash") val chunkHash: String,
    @ColumnInfo(name = "content_base64") val contentBase64: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

@Entity(tableName = "codex_tombstones", primaryKeys = ["machine_id", "thread_id"])
data class CodexTombstoneEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "deletion_revision") val deletionRevision: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
)
