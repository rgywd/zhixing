package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.CodexApprovalEntity
import me.rerere.rikkahub.data.db.entity.CodexCatalogSyncEntity
import me.rerere.rikkahub.data.db.entity.CodexCatalogChunkEntity
import me.rerere.rikkahub.data.db.entity.CodexItemEntity
import me.rerere.rikkahub.data.db.entity.CodexMachineEntity
import me.rerere.rikkahub.data.db.entity.CodexProjectEntity
import me.rerere.rikkahub.data.db.entity.CodexRuntimeBindingEntity
import me.rerere.rikkahub.data.db.entity.CodexThreadEntity
import me.rerere.rikkahub.data.db.entity.CodexTombstoneEntity
import me.rerere.rikkahub.data.db.entity.CodexTurnEntity

@Dao
interface CodexCatalogDAO {
    @Query("SELECT * FROM codex_machines ORDER BY last_seen_at DESC")
    fun observeMachines(): Flow<List<CodexMachineEntity>>

    @Query("SELECT * FROM codex_projects ORDER BY updated_at DESC")
    fun observeProjects(): Flow<List<CodexProjectEntity>>

    @Query("SELECT * FROM codex_threads ORDER BY recency_at DESC")
    fun observeThreads(): Flow<List<CodexThreadEntity>>

    @Query("SELECT * FROM codex_threads WHERE machine_id = :machineId AND thread_id = :threadId")
    fun observeThread(machineId: String, threadId: String): Flow<CodexThreadEntity?>

    @Query(
        "SELECT * FROM codex_turns WHERE machine_id = :machineId AND thread_id = :threadId " +
            "ORDER BY position ASC"
    )
    fun observeTurns(machineId: String, threadId: String): Flow<List<CodexTurnEntity>>

    @Query(
        "SELECT * FROM codex_items WHERE machine_id = :machineId AND thread_id = :threadId " +
            "ORDER BY turn_id ASC, position ASC"
    )
    fun observeItems(machineId: String, threadId: String): Flow<List<CodexItemEntity>>

    @Query(
        "SELECT * FROM codex_approvals WHERE machine_id = :machineId AND thread_id = :threadId " +
            "ORDER BY created_at ASC"
    )
    fun observeApprovals(machineId: String, threadId: String): Flow<List<CodexApprovalEntity>>

    @Query(
        "SELECT DISTINCT t.* FROM codex_threads t " +
            "LEFT JOIN codex_projects p ON p.project_id = t.project_id " +
            "LEFT JOIN codex_items i ON i.machine_id = t.machine_id AND i.thread_id = t.thread_id " +
            "WHERE t.name LIKE '%' || :query || '%' OR t.preview LIKE '%' || :query || '%' " +
            "OR p.display_name LIKE '%' || :query || '%' OR p.canonical_root LIKE '%' || :query || '%' " +
            "OR i.text LIKE '%' || :query || '%' ORDER BY t.recency_at DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int = 100): List<CodexThreadEntity>

    @Query("SELECT revision FROM codex_catalog_sync WHERE machine_id = :machineId")
    suspend fun revision(machineId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMachine(machine: CodexMachineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjects(projects: List<CodexProjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertThreads(threads: List<CodexThreadEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSync(sync: CodexCatalogSyncEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCatalogChunk(chunk: CodexCatalogChunkEntity)

    @Query("SELECT * FROM codex_catalog_chunks WHERE snapshot_id = :snapshotId ORDER BY chunk_index ASC")
    suspend fun catalogChunks(snapshotId: String): List<CodexCatalogChunkEntity>

    @Query("DELETE FROM codex_catalog_chunks WHERE snapshot_id = :snapshotId")
    suspend fun deleteCatalogChunks(snapshotId: String)

    @Query("DELETE FROM codex_catalog_chunks WHERE received_at < :cutoff")
    suspend fun deleteOldCatalogChunks(cutoff: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTurns(turns: List<CodexTurnEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<CodexItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRuntimeBinding(binding: CodexRuntimeBindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApprovals(approvals: List<CodexApprovalEntity>)

    @Query(
        "UPDATE codex_threads SET runtime_state = :state, updated_at = :updatedAt " +
            "WHERE machine_id = :machineId AND thread_id = :threadId"
    )
    suspend fun updateThreadRuntime(machineId: String, threadId: String, state: String, updatedAt: Long)

    @Query(
        "UPDATE codex_threads SET archived = :archived, updated_at = :updatedAt " +
            "WHERE machine_id = :machineId AND thread_id = :threadId"
    )
    suspend fun updateThreadArchived(machineId: String, threadId: String, archived: Boolean, updatedAt: Long)

    @Query(
        "SELECT COALESCE(MAX(position), -1) + 1 FROM codex_turns " +
            "WHERE machine_id = :machineId AND thread_id = :threadId"
    )
    suspend fun nextTurnPosition(machineId: String, threadId: String): Int

    @Query(
        "SELECT COALESCE(MAX(position), -1) + 1 FROM codex_items " +
            "WHERE machine_id = :machineId AND thread_id = :threadId AND turn_id = :turnId"
    )
    suspend fun nextItemPosition(machineId: String, threadId: String, turnId: String): Int

    @Query(
        "SELECT position FROM codex_turns WHERE machine_id = :machineId AND thread_id = :threadId AND turn_id = :turnId"
    )
    suspend fun turnPosition(machineId: String, threadId: String, turnId: String): Int?

    @Query(
        "SELECT position FROM codex_items WHERE machine_id = :machineId AND thread_id = :threadId " +
            "AND turn_id = :turnId AND item_id = :itemId"
    )
    suspend fun itemPosition(machineId: String, threadId: String, turnId: String, itemId: String): Int?

    @Query(
        "DELETE FROM codex_approvals WHERE machine_id = :machineId AND thread_id = :threadId AND approval_id = :approvalId"
    )
    suspend fun deleteApproval(machineId: String, threadId: String, approvalId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(tombstone: CodexTombstoneEntity)

    @Query("DELETE FROM codex_projects WHERE machine_id = :machineId")
    suspend fun deleteProjectsForMachine(machineId: String)

    @Query("DELETE FROM codex_threads WHERE machine_id = :machineId")
    suspend fun deleteThreadsForMachine(machineId: String)

    @Query("DELETE FROM codex_turns WHERE machine_id = :machineId AND thread_id = :threadId")
    suspend fun deleteTurns(machineId: String, threadId: String)

    @Query("DELETE FROM codex_items WHERE machine_id = :machineId AND thread_id = :threadId")
    suspend fun deleteItems(machineId: String, threadId: String)

    @Query("DELETE FROM codex_approvals WHERE machine_id = :machineId AND thread_id = :threadId")
    suspend fun deleteApprovals(machineId: String, threadId: String)

    @Query("DELETE FROM codex_runtime_bindings WHERE machine_id = :machineId AND thread_id = :threadId")
    suspend fun deleteRuntimeBinding(machineId: String, threadId: String)

    @Query("DELETE FROM codex_threads WHERE machine_id = :machineId AND thread_id = :threadId")
    suspend fun deleteThread(machineId: String, threadId: String)

    @Query("SELECT thread_id FROM codex_threads WHERE machine_id = :machineId")
    suspend fun threadIds(machineId: String): List<String>

    @Query("DELETE FROM codex_turns WHERE machine_id = :machineId AND thread_id IN (:threadIds)")
    suspend fun deleteTurnsByThreadIds(machineId: String, threadIds: List<String>)

    @Query("DELETE FROM codex_items WHERE machine_id = :machineId AND thread_id IN (:threadIds)")
    suspend fun deleteItemsByThreadIds(machineId: String, threadIds: List<String>)

    @Transaction
    suspend fun replaceSnapshot(
        machine: CodexMachineEntity,
        projects: List<CodexProjectEntity>,
        threads: List<CodexThreadEntity>,
        sync: CodexCatalogSyncEntity,
    ) {
        val staleThreadIds = threadIds(machine.machineId) - threads.mapTo(hashSetOf()) { it.threadId }
        if (staleThreadIds.isNotEmpty()) {
            deleteItemsByThreadIds(machine.machineId, staleThreadIds)
            deleteTurnsByThreadIds(machine.machineId, staleThreadIds)
        }
        deleteProjectsForMachine(machine.machineId)
        deleteThreadsForMachine(machine.machineId)
        upsertMachine(machine)
        if (projects.isNotEmpty()) upsertProjects(projects)
        if (threads.isNotEmpty()) upsertThreads(threads)
        upsertSync(sync)
    }

    @Transaction
    suspend fun replaceThreadDetail(
        machineId: String,
        threadId: String,
        turns: List<CodexTurnEntity>,
        items: List<CodexItemEntity>,
    ) {
        deleteItems(machineId, threadId)
        deleteTurns(machineId, threadId)
        if (turns.isNotEmpty()) upsertTurns(turns)
        if (items.isNotEmpty()) upsertItems(items)
    }

    @Transaction
    suspend fun deleteThreadWithDetails(machineId: String, threadId: String) {
        deleteItems(machineId, threadId)
        deleteTurns(machineId, threadId)
        deleteApprovals(machineId, threadId)
        deleteRuntimeBinding(machineId, threadId)
        deleteThread(machineId, threadId)
    }
}
