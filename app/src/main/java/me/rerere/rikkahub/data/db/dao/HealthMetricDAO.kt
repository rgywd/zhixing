package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.HealthMetricRecordEntity

@Dao
interface HealthMetricDAO {
    @Query(
        """
        SELECT * FROM health_metric_records
        ORDER BY effective_at_epoch_ms DESC, recorded_at_epoch_ms DESC, id DESC
        """,
    )
    fun observeAll(): Flow<List<HealthMetricRecordEntity>>

    @Query(
        """
        SELECT * FROM health_metric_records
        ORDER BY effective_at_epoch_ms DESC, recorded_at_epoch_ms DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecent(limit: Int): List<HealthMetricRecordEntity>

    @Query("SELECT * FROM health_metric_records WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<HealthMetricRecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(records: List<HealthMetricRecordEntity>): List<Long>

    @Query("DELETE FROM health_metric_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int
}
