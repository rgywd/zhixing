package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AgendaPlanEntity
import me.rerere.rikkahub.data.db.entity.AgendaPlanStageEntity
import me.rerere.rikkahub.data.db.entity.AgendaPlanWithStagesEntity

@Dao
interface AgendaPlanDAO {
    @Transaction
    @Query(
        """
        SELECT * FROM agenda_plans
        WHERE status != 'CANCELLED'
        ORDER BY
            CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END,
            CASE WHEN event_at IS NULL THEN 1 ELSE 0 END,
            event_at ASC,
            updated_at DESC
        """
    )
    fun observeVisiblePlans(): Flow<List<AgendaPlanWithStagesEntity>>

    @Transaction
    @Query(
        """
        SELECT * FROM agenda_plans
        WHERE status != 'CANCELLED'
        ORDER BY
            CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END,
            CASE WHEN event_at IS NULL THEN 1 ELSE 0 END,
            event_at ASC,
            updated_at DESC
        """
    )
    suspend fun getVisiblePlans(): List<AgendaPlanWithStagesEntity>

    @Transaction
    @Query("SELECT * FROM agenda_plans WHERE id = :id")
    suspend fun getById(id: String): AgendaPlanWithStagesEntity?

    @Query("SELECT * FROM agenda_plan_stages WHERE id = :id")
    suspend fun getStageById(id: String): AgendaPlanStageEntity?

    @Query(
        """
        SELECT * FROM agenda_plan_stages
        WHERE status = 'PENDING' AND reminder_at IS NOT NULL
        ORDER BY reminder_at ASC
        """
    )
    suspend fun getPendingReminderStages(): List<AgendaPlanStageEntity>

    @Upsert
    suspend fun upsertPlan(plan: AgendaPlanEntity)

    @Upsert
    suspend fun upsertStages(stages: List<AgendaPlanStageEntity>)

    @Upsert
    suspend fun upsertStage(stage: AgendaPlanStageEntity)

    @Transaction
    suspend fun upsertPlanWithStages(
        plan: AgendaPlanEntity,
        stages: List<AgendaPlanStageEntity>,
    ) {
        upsertPlan(plan)
        upsertStages(stages)
    }
}
