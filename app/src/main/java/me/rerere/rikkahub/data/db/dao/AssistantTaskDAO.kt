package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AssistantTaskEntity
import me.rerere.rikkahub.data.db.entity.AssistantTaskEventEntity
import me.rerere.rikkahub.data.db.entity.AssistantTaskLinkEntity
import me.rerere.rikkahub.data.db.entity.AssistantRuntimeContextEntity

@Dao
interface AssistantTaskDAO {
    @Query("SELECT * FROM assistant_tasks ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<AssistantTaskEntity>>

    @Query("SELECT * FROM assistant_tasks WHERE id = :taskId")
    suspend fun getTask(taskId: String): AssistantTaskEntity?

    @Query(
        """
        SELECT * FROM assistant_tasks
        WHERE conversation_id = :conversationId
          AND status IN ('RUNNING', 'WAITING_FOR_INPUT')
        ORDER BY updated_at DESC
        LIMIT 1
        """
    )
    suspend fun findActiveForConversation(conversationId: String): AssistantTaskEntity?

    @Query(
        """
        SELECT * FROM assistant_tasks
        WHERE conversation_id = :conversationId AND status = 'FAILED_RETRYABLE'
        ORDER BY updated_at DESC
        LIMIT 1
        """
    )
    suspend fun findRetryableForConversation(conversationId: String): AssistantTaskEntity?

    @Query("SELECT * FROM assistant_tasks WHERE status = 'RUNNING'")
    suspend fun getRunningTasks(): List<AssistantTaskEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(task: AssistantTaskEntity)

    @Update
    suspend fun updateTask(task: AssistantTaskEntity)

    @Query("SELECT COALESCE(MAX(seq), 0) FROM assistant_task_events WHERE task_id = :taskId")
    suspend fun maxEventSeq(taskId: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: AssistantTaskEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLink(link: AssistantTaskLinkEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRuntimeContext(context: AssistantRuntimeContextEntity)

    @Query("DELETE FROM assistant_runtime_contexts WHERE id = :contextId")
    suspend fun deleteRuntimeContext(contextId: String)

    @Query("SELECT * FROM assistant_task_events WHERE task_id = :taskId ORDER BY seq")
    fun observeEvents(taskId: String): Flow<List<AssistantTaskEventEntity>>

    @Query("DELETE FROM assistant_task_links WHERE object_type = 'CONVERSATION' AND object_id = :conversationId")
    suspend fun invalidateConversationLinks(conversationId: String)

    @Query("DELETE FROM assistant_runtime_contexts WHERE conversation_id = :conversationId")
    suspend fun deleteRuntimeContextsForConversation(conversationId: String)
}
