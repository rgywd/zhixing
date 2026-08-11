package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryDocumentEntity

@Dao
interface MemoryDocumentDAO {
    @Query(
        """
        SELECT * FROM MemoryDocumentEntity
        WHERE scope_id IN (:scopeIds) AND state = 'ACTIVE'
        ORDER BY path ASC
        """
    )
    fun observeActive(scopeIds: List<String>): Flow<List<MemoryDocumentEntity>>

    @Query(
        """
        SELECT * FROM MemoryDocumentEntity
        WHERE scope_id IN (:scopeIds) AND state = 'ACTIVE'
        ORDER BY path ASC
        """
    )
    suspend fun listActive(scopeIds: List<String>): List<MemoryDocumentEntity>

    @Query("SELECT * FROM MemoryDocumentEntity WHERE sources_json != '[]'")
    suspend fun listWithSources(): List<MemoryDocumentEntity>

    @Query("SELECT * FROM MemoryDocumentEntity WHERE scope_id = :scopeId AND path = :path")
    suspend fun find(scopeId: String, path: String): MemoryDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: MemoryDocumentEntity): Long

    @Query(
        """
        UPDATE MemoryDocumentEntity
        SET name = :name,
            description = :description,
            aliases_json = :aliasesJson,
            content = :content,
            sources_json = :sourcesJson,
            version = version + 1,
            updated_at = :updatedAt
        WHERE scope_id = :scopeId AND path = :path
          AND version = :expectedVersion AND state = 'ACTIVE'
        """
    )
    suspend fun compareAndSet(
        scopeId: String,
        path: String,
        expectedVersion: Long,
        name: String,
        description: String,
        aliasesJson: String,
        content: String,
        sourcesJson: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE MemoryDocumentEntity
        SET name = :name,
            description = :description,
            aliases_json = :aliasesJson,
            content = :content,
            sources_json = :sourcesJson,
            state = 'ACTIVE',
            version = version + 1,
            updated_at = :updatedAt
        WHERE scope_id = :scopeId AND path = :path AND state = 'DELETED'
        """
    )
    suspend fun reactivateDeleted(
        scopeId: String,
        path: String,
        name: String,
        description: String,
        aliasesJson: String,
        content: String,
        sourcesJson: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE MemoryDocumentEntity
        SET name = '',
            description = '',
            aliases_json = '[]',
            content = '',
            sources_json = '[]',
            state = 'DELETED',
            version = version + 1,
            updated_at = :updatedAt
        WHERE scope_id = :scopeId AND path = :path
          AND version = :expectedVersion AND state = 'ACTIVE'
        """
    )
    suspend fun compareAndDelete(
        scopeId: String,
        path: String,
        expectedVersion: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE MemoryDocumentEntity
        SET sources_json = :sourcesJson,
            version = version + 1,
            updated_at = :updatedAt
        WHERE scope_id = :scopeId AND path = :path AND version = :expectedVersion
        """
    )
    suspend fun compareAndSetSources(
        scopeId: String,
        path: String,
        expectedVersion: Long,
        sourcesJson: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM MemoryDocumentEntity WHERE scope_id = :scopeId")
    suspend fun deleteScope(scopeId: String)
}
