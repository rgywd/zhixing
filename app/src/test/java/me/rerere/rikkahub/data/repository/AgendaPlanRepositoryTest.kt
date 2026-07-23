package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.agenda.AgendaPlanReminderGateway
import me.rerere.rikkahub.data.db.dao.AgendaPlanDAO
import me.rerere.rikkahub.data.db.entity.AgendaPlanEntity
import me.rerere.rikkahub.data.db.entity.AgendaPlanStageEntity
import me.rerere.rikkahub.data.db.entity.AgendaPlanWithStagesEntity
import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStageDraft
import me.rerere.rikkahub.data.model.AgendaPlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaPlanRepositoryTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `creating a plan persists all stages but schedules only the current stage`() = runBlocking {
        val dao = FakeAgendaPlanDao()
        val reminders = FakeReminderGateway()
        val repository = AgendaPlanRepository(dao, reminders) { now }

        val created = repository.create(
            title = "公司年会",
            eventAt = now + days(15),
            stages = listOf(
                AgendaPlanStageDraft(
                    title = "购买车票",
                    triggerOffsetMinutes = -15 * 24 * 60L,
                    reminderOffsetMinutes = -15 * 24 * 60L,
                ),
                AgendaPlanStageDraft(
                    title = "确认天气",
                    triggerOffsetMinutes = -3 * 24 * 60L,
                    reminderOffsetMinutes = -3 * 24 * 60L,
                ),
            ),
        )

        assertEquals(2, created.stages.size)
        assertEquals(listOf(created.stages.first().id), reminders.synced)
        assertEquals(now, created.stages.first().triggerAt)
        assertEquals(now + days(12), created.stages.last().triggerAt)
    }

    @Test
    fun `completing a stage keeps parent active and advances reminder to next stage`() = runBlocking {
        val dao = FakeAgendaPlanDao()
        val reminders = FakeReminderGateway()
        val repository = AgendaPlanRepository(dao, reminders) { now }
        val created = repository.create(
            title = "公司年会",
            eventAt = now + days(15),
            stages = listOf(
                AgendaPlanStageDraft("购买车票", reminderOffsetMinutes = -15 * 24 * 60L),
                AgendaPlanStageDraft("确认天气", reminderOffsetMinutes = -3 * 24 * 60L),
            ),
        )
        reminders.synced.clear()

        val updated = repository.setStageCompleted(created.stages.first().id, true)

        assertEquals(AgendaPlanStatus.ACTIVE, updated.plan.status)
        assertEquals(listOf(created.stages.last().id), reminders.synced)
        assertNotEquals(updated.plan.completedAt, now)
    }

    @Test
    fun `moving parent event recalculates relative times but preserves absolute times`() = runBlocking {
        val dao = FakeAgendaPlanDao()
        val repository = AgendaPlanRepository(dao, FakeReminderGateway()) { now }
        val absolute = now + days(2)
        val created = repository.create(
            title = "公司年会",
            eventAt = now + days(15),
            stages = listOf(
                AgendaPlanStageDraft("购买车票", triggerOffsetMinutes = -15 * 24 * 60L),
                AgendaPlanStageDraft("固定日期取材料", triggerAt = absolute),
            ),
        )

        val moved = repository.updatePlan(
            id = created.plan.id,
            title = created.plan.title,
            note = created.plan.note,
            location = created.plan.location,
            eventAt = now + days(20),
            sourceReference = null,
        )

        assertEquals(now + days(5), moved.stages.first().triggerAt)
        assertEquals(absolute, moved.stages.last().triggerAt)
    }

    @Test
    fun `invalid mixed absolute and relative stage does not persist a partial plan`() = runBlocking {
        val dao = FakeAgendaPlanDao()
        val repository = AgendaPlanRepository(dao, FakeReminderGateway()) { now }

        val result = runCatching {
            repository.create(
                title = "公司年会",
                eventAt = now + days(15),
                stages = listOf(
                    AgendaPlanStageDraft(
                        title = "购买车票",
                        triggerAt = now,
                        triggerOffsetMinutes = -15,
                    )
                ),
            )
        }

        assertTrue(result.isFailure)
        assertTrue(dao.plans.isEmpty())
        assertTrue(dao.stages.isEmpty())
    }

    @Test
    fun `reconciliation rebuilds only the current pending stage reminder`() = runBlocking {
        val dao = FakeAgendaPlanDao()
        val reminders = FakeReminderGateway()
        val repository = AgendaPlanRepository(dao, reminders) { now }
        val created = repository.create(
            title = "公司年会",
            eventAt = now + days(15),
            stages = listOf(
                AgendaPlanStageDraft("购买车票", reminderOffsetMinutes = -15 * 24 * 60L),
                AgendaPlanStageDraft("确认天气", reminderOffsetMinutes = -3 * 24 * 60L),
            ),
        )
        reminders.synced.clear()

        repository.reconcileReminders()

        assertEquals(listOf(created.stages.first().id), reminders.synced)
    }

    @Test
    fun `stale worker payload is rejected after stage completion`() = runBlocking {
        val dao = FakeAgendaPlanDao()
        val repository = AgendaPlanRepository(dao, FakeReminderGateway()) { now }
        val created = repository.create(
            title = "公司年会",
            eventAt = now + days(15),
            stages = listOf(
                AgendaPlanStageDraft("购买车票", reminderOffsetMinutes = -15 * 24 * 60L),
                AgendaPlanStageDraft("确认天气", reminderOffsetMinutes = -3 * 24 * 60L),
            ),
        )

        repository.setStageCompleted(created.stages.first().id, true)

        assertEquals(null, repository.reminderPayload(created.stages.first().id))
        assertEquals(created.stages.last().id, repository.reminderPayload(created.stages.last().id)?.stageId)
    }

    @Test
    fun `stale expected reminder time is rejected after reschedule`() = runBlocking {
        val dao = FakeAgendaPlanDao()
        val repository = AgendaPlanRepository(dao, FakeReminderGateway()) { now }
        val created = repository.create(
            title = "公司年会",
            eventAt = now + days(15),
            stages = listOf(AgendaPlanStageDraft("购买车票", reminderOffsetMinutes = -15 * 24 * 60L)),
        )
        val oldReminderAt = created.stages.single().reminderAt!!
        repository.updatePlan(
            id = created.plan.id,
            title = created.plan.title,
            note = created.plan.note,
            location = created.plan.location,
            eventAt = now + days(20),
            sourceReference = null,
        )

        assertEquals(null, repository.reminderPayload(created.stages.single().id, oldReminderAt))
    }

    private fun days(value: Long) = value * 24 * 60 * 60 * 1000
}

private class FakeReminderGateway : AgendaPlanReminderGateway {
    val synced = mutableListOf<String>()
    val cancelled = mutableListOf<String>()

    override fun sync(stage: AgendaPlanStage) {
        synced += stage.id
    }

    override fun cancel(stageId: String) {
        cancelled += stageId
    }
}

private class FakeAgendaPlanDao : AgendaPlanDAO {
    val plans = linkedMapOf<String, AgendaPlanEntity>()
    val stages = linkedMapOf<String, AgendaPlanStageEntity>()

    override fun observeVisiblePlans(): Flow<List<AgendaPlanWithStagesEntity>> = flowOf(visible())

    override suspend fun getVisiblePlans(): List<AgendaPlanWithStagesEntity> = visible()

    override suspend fun getById(id: String): AgendaPlanWithStagesEntity? =
        plans[id]?.let { plan ->
            AgendaPlanWithStagesEntity(plan, stages.values.filter { it.planId == id })
        }

    override suspend fun getStageById(id: String): AgendaPlanStageEntity? = stages[id]

    override suspend fun getPendingReminderStages(): List<AgendaPlanStageEntity> =
        stages.values.filter { it.status == "PENDING" && it.reminderAt != null }

    override suspend fun upsertPlan(plan: AgendaPlanEntity) {
        plans[plan.id] = plan
    }

    override suspend fun upsertStages(stages: List<AgendaPlanStageEntity>) {
        stages.forEach { this.stages[it.id] = it }
    }

    override suspend fun upsertStage(stage: AgendaPlanStageEntity) {
        stages[stage.id] = stage
    }

    private fun visible(): List<AgendaPlanWithStagesEntity> = plans.values
        .filter { it.status != "CANCELLED" }
        .map { plan -> AgendaPlanWithStagesEntity(plan, stages.values.filter { it.planId == plan.id }) }
}
