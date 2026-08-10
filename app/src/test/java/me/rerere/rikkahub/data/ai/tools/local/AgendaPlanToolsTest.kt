package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.agenda.AgendaPlanReminderGateway
import me.rerere.rikkahub.data.db.dao.AgendaPlanDAO
import me.rerere.rikkahub.data.db.entity.AgendaPlanEntity
import me.rerere.rikkahub.data.db.entity.AgendaPlanStageEntity
import me.rerere.rikkahub.data.db.entity.AgendaPlanWithStagesEntity
import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaPlanToolsTest {
    @Test
    fun `legacy plan approval metadata remains compatible`() {
        val tools = buildAgendaPlanTools(repository())
        val reads = setOf("plan_list", "plan_get")
        val writes = setOf(
            "plan_create",
            "plan_update",
            "plan_stage_update",
            "plan_stage_complete",
            "plan_set_status",
        )

        assertTrue(tools.filter { it.name in reads }.all { !it.needsApproval(buildJsonObject {}) })
        assertTrue(tools.filter { it.name in writes }.all { it.needsApproval(buildJsonObject {}) })
        assertEquals(reads + writes, tools.map { it.name }.toSet())
    }

    @Test
    fun `plan create schema accepts stages as one nested approved write`() = runBlocking {
        val dao = ToolFakeAgendaPlanDao()
        val tools = buildAgendaPlanTools(repository(dao), conversationId = "conversation-1")
        val create = tools.single { it.name == "plan_create" }
        val schema = create.parameters() as InputSchema.Obj

        assertEquals("array", schema.properties["stages"]!!.jsonObject["type"].toString().trim('"'))
        create.execute(
            buildJsonObject {
                put("title", "公司年会")
                put("event_at", "2026-08-20 10:00")
                put("stages", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "购买车票")
                        put("trigger_offset_minutes", -21600)
                        put("reminder_offset_minutes", -21600)
                    })
                    add(buildJsonObject {
                        put("title", "确认天气")
                        put("trigger_offset_minutes", -4320)
                    })
                })
            }
        )

        assertEquals(1, dao.plans.size)
        assertEquals(2, dao.stages.size)
        assertEquals("conversation-1", dao.plans.values.single().conversationId)
    }

    private fun repository(dao: ToolFakeAgendaPlanDao = ToolFakeAgendaPlanDao()) =
        AgendaPlanRepository(
            dao = dao,
            reminderGateway = object : AgendaPlanReminderGateway {
                override fun sync(stage: AgendaPlanStage) = Unit
                override fun cancel(stageId: String) = Unit
            },
            now = { 1_800_000_000_000L },
        )
}

private class ToolFakeAgendaPlanDao : AgendaPlanDAO {
    val plans = linkedMapOf<String, AgendaPlanEntity>()
    val stages = linkedMapOf<String, AgendaPlanStageEntity>()

    override fun observeVisiblePlans(): Flow<List<AgendaPlanWithStagesEntity>> = flowOf(visible())
    override suspend fun getVisiblePlans(): List<AgendaPlanWithStagesEntity> = visible()
    override suspend fun getById(id: String): AgendaPlanWithStagesEntity? =
        plans[id]?.let { AgendaPlanWithStagesEntity(it, stages.values.filter { stage -> stage.planId == id }) }

    override suspend fun getStageById(id: String): AgendaPlanStageEntity? = stages[id]
    override suspend fun getPendingReminderStages(): List<AgendaPlanStageEntity> = stages.values.toList()

    override suspend fun upsertPlan(plan: AgendaPlanEntity) {
        plans[plan.id] = plan
    }

    override suspend fun upsertStages(stages: List<AgendaPlanStageEntity>) {
        stages.forEach { this.stages[it.id] = it }
    }

    override suspend fun upsertStage(stage: AgendaPlanStageEntity) {
        stages[stage.id] = stage
    }

    private fun visible() = plans.values.map { plan ->
        AgendaPlanWithStagesEntity(plan, stages.values.filter { it.planId == plan.id })
    }
}
