package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
          AND updated_at > :retryCutoffMillis
          AND (
            (
              (:anchorMessageId IS NOT NULL OR :anchorNodeId IS NOT NULL)
              AND (:anchorMessageId IS NULL OR anchor_message_id = :anchorMessageId)
              AND (:anchorNodeId IS NULL OR anchor_node_id = :anchorNodeId)
            )
            OR (
              :anchorMessageId IS NULL AND :anchorNodeId IS NULL
              AND anchor_message_id IS NULL AND anchor_node_id IS NULL
            )
          )
        ORDER BY updated_at DESC
        LIMIT 1
        """
    )
    suspend fun findRetryableForSource(
        conversationId: String,
        anchorMessageId: String?,
        anchorNodeId: String?,
        retryCutoffMillis: Long,
    ): AssistantTaskEntity?

    @Query("SELECT * FROM assistant_tasks WHERE status = 'RUNNING'")
    suspend fun getRunningTasks(): List<AssistantTaskEntity>

    @Query("SELECT id FROM assistant_tasks WHERE conversation_id = :conversationId")
    suspend fun getTaskIdsForConversation(conversationId: String): List<String>

    @Query(
        """
        SELECT id FROM assistant_tasks
        WHERE conversation_id = :conversationId AND status = 'FAILED_RETRYABLE'
        """
    )
    suspend fun getRetryableTaskIdsForConversation(conversationId: String): List<String>

    @Query(
        """
        SELECT id FROM assistant_tasks
        WHERE status = 'FAILED_RETRYABLE' AND updated_at <= :cutoffMillis
        """
    )
    suspend fun getExpiredRetryableTaskIds(cutoffMillis: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(task: AssistantTaskEntity)

    @Update
    suspend fun updateTask(task: AssistantTaskEntity)

    @Query("DELETE FROM assistant_task_links WHERE task_id IN (:taskIds)")
    suspend fun deleteLinksForTasks(taskIds: List<String>)

    @Query("DELETE FROM assistant_task_events WHERE task_id IN (:taskIds)")
    suspend fun deleteEventsForTasks(taskIds: List<String>)

    @Query("DELETE FROM assistant_tasks WHERE id IN (:taskIds)")
    suspend fun deleteTasks(taskIds: List<String>): Int

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

    @Query("DELETE FROM assistant_runtime_contexts WHERE conversation_id = :conversationId")
    suspend fun deleteRuntimeContextsForConversation(conversationId: String)

    @Transaction
    suspend fun deleteTaskRecords(taskIds: List<String>): Int {
        if (taskIds.isEmpty()) return 0
        deleteLinksForTasks(taskIds)
        deleteEventsForTasks(taskIds)
        return deleteTasks(taskIds)
    }

    @Transaction
    suspend fun deleteTasksForConversation(conversationId: String): Int =
        deleteTaskRecords(getTaskIdsForConversation(conversationId))
}
