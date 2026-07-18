package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkMachineEntity
import me.rerere.rikkahub.data.db.entity.WorkMessageEntity
import me.rerere.rikkahub.data.db.entity.WorkRepoPresetEntity
import me.rerere.rikkahub.data.db.entity.WorkSessionEntity

@Dao
interface WorkSessionDAO {
    @Query("SELECT * FROM work_sessions ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<WorkSessionEntity>>

    @Query("SELECT * FROM work_sessions WHERE id = :id")
    fun observeById(id: String): Flow<WorkSessionEntity?>

    @Query("SELECT * FROM work_sessions WHERE id = :id")
    suspend fun getById(id: String): WorkSessionEntity?

    @Query("SELECT id, last_permission_mode FROM work_sessions WHERE last_permission_mode IS NOT NULL")
    suspend fun getPermissionModes(): List<SessionPermissionMode>

    @Query(
        "SELECT * FROM work_sessions WHERE name LIKE '%' || :query || '%' OR path LIKE '%' || :query || '%' " +
            "ORDER BY updated_at DESC"
    )
    suspend fun searchByNameOrPath(query: String): List<WorkSessionEntity>

    @Query("SELECT * FROM work_sessions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<WorkSessionEntity>

    @Query("UPDATE work_sessions SET last_permission_mode = :mode WHERE id = :id")
    suspend fun updatePermissionMode(id: String, mode: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<WorkSessionEntity>)

    @Query("DELETE FROM work_sessions WHERE id NOT IN (:ids)")
    suspend fun deleteNotIn(ids: List<String>)

    @Query("DELETE FROM work_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Transaction
    suspend fun replaceAll(sessions: List<WorkSessionEntity>) {
        upsertAll(sessions)
        deleteNotIn(sessions.map(WorkSessionEntity::id))
    }

    @Query("DELETE FROM work_sessions")
    suspend fun clearAll()
}

data class SessionPermissionMode(
    @androidx.room.ColumnInfo("id") val id: String,
    @androidx.room.ColumnInfo("last_permission_mode") val lastPermissionMode: String?,
)

@Dao
interface WorkMessageDAO {
    @Query("SELECT * FROM work_messages WHERE session_id = :sessionId ORDER BY seq ASC")
    fun observeBySession(sessionId: String): Flow<List<WorkMessageEntity>>

    @Query("SELECT MAX(seq) FROM work_messages WHERE session_id = :sessionId")
    suspend fun maxSeq(sessionId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<WorkMessageEntity>)

    @Query("DELETE FROM work_messages WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM work_messages WHERE session_id NOT IN (:sessionIds)")
    suspend fun deleteSessionsNotIn(sessionIds: List<String>)

    @Query("SELECT DISTINCT session_id FROM work_messages WHERE parts LIKE '%' || :query || '%'")
    suspend fun searchSessionIds(query: String): List<String>

    @Query("SELECT * FROM work_messages")
    suspend fun getAll(): List<WorkMessageEntity>

    @Query("DELETE FROM work_messages")
    suspend fun clearAll()
}

@Dao
interface WorkMachineDAO {
    @Query("SELECT * FROM work_machines ORDER BY active DESC, active_at DESC")
    fun observeAll(): Flow<List<WorkMachineEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(machines: List<WorkMachineEntity>)

    @Query("DELETE FROM work_machines WHERE id NOT IN (:ids)")
    suspend fun deleteNotIn(ids: List<String>)

    @Transaction
    suspend fun replaceAll(machines: List<WorkMachineEntity>) {
        upsertAll(machines)
        deleteNotIn(machines.map(WorkMachineEntity::id))
    }

    @Query("DELETE FROM work_machines")
    suspend fun clearAll()
}

@Dao
interface WorkRepoPresetDAO {
    @Query("SELECT * FROM work_repo_presets ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<WorkRepoPresetEntity>>

    @Query("SELECT * FROM work_repo_presets WHERE id = :id")
    suspend fun getById(id: String): WorkRepoPresetEntity?

    @Query("SELECT * FROM work_repo_presets")
    suspend fun getAll(): List<WorkRepoPresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: WorkRepoPresetEntity)

    @Query("DELETE FROM work_repo_presets WHERE id = :id")
    suspend fun deleteById(id: String)
}
