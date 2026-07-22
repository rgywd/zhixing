package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AgendaTaskEntity

@Dao
interface AgendaTaskDAO {
    @Query(
        """
        SELECT * FROM agenda_tasks
        WHERE status != 'CANCELLED'
        ORDER BY
            CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END,
            CASE WHEN due_at IS NULL THEN 1 ELSE 0 END,
            due_at ASC,
            updated_at DESC
        """
    )
    fun observeVisibleTasks(): Flow<List<AgendaTaskEntity>>

    @Query("SELECT * FROM agenda_tasks WHERE id = :id")
    suspend fun getById(id: String): AgendaTaskEntity?

    @Query("SELECT * FROM agenda_tasks WHERE status != 'CANCELLED' ORDER BY updated_at DESC")
    suspend fun getVisibleTasks(): List<AgendaTaskEntity>

    @Upsert
    suspend fun upsert(task: AgendaTaskEntity)

    @Query("DELETE FROM agenda_tasks WHERE id = :id")
    suspend fun delete(id: String)
}
