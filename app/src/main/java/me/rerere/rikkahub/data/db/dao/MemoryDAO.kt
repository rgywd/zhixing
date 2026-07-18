package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND state = 'ACTIVE' ORDER BY updated_at DESC, id DESC")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND state = 'ACTIVE' ORDER BY updated_at DESC, id DESC")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE state = 'ACTIVE' ORDER BY updated_at DESC, id DESC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE state = 'ACTIVE' ORDER BY updated_at DESC, id DESC")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY updated_at DESC, id DESC")
    fun getAllMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query(
        """
        SELECT * FROM memoryentity
        WHERE assistant_id = :assistantId AND kind = :kind AND state = 'ACTIVE'
        ORDER BY updated_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getActiveMemoriesOfKind(assistantId: String, kind: String, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
