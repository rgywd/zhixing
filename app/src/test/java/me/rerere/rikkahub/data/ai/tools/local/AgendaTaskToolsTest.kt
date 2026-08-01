package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.model.AgendaRecurrenceFrequency
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import me.rerere.rikkahub.data.repository.FakeAgendaTaskDao
import me.rerere.rikkahub.data.repository.FakeAgendaTaskReminderGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaTaskToolsTest {
    @Test
    fun `task tools expose additive recurrence contract and create a recurring task`() = runBlocking {
        val dao = FakeAgendaTaskDao()
        val now = 1_800_000_000_000L
        val repository = AgendaTaskRepository(dao, FakeAgendaTaskReminderGateway()) { now }
        val create = buildAgendaTaskTools(repository).single { it.name == "task_create" }
        val schema = create.parameters() as InputSchema.Obj

        assertTrue(schema.properties.containsKey("recurrence_frequency"))
        assertTrue(schema.properties.containsKey("recurrence_interval"))
        create.execute(buildJsonObject {
            put("title", "晨间简报")
            put("due_at", (now + 60_000L).toString())
            put("reminder_at", (now + 60_000L).toString())
            put("recurrence_frequency", "DAILY")
            put("recurrence_interval", 2)
        })

        val stored = dao.tasks.values.single()
        assertEquals(AgendaRecurrenceFrequency.DAILY.name, stored.recurrenceFrequency)
        assertEquals(2, stored.recurrenceInterval)
    }

    @Test
    fun `calendar create schema exposes the same recurrence fields`() {
        val schema = calendarCreateInputSchema()

        assertEquals("string", schema.properties["recurrence_frequency"]!!.jsonObject["type"].toString().trim('"'))
        assertEquals("integer", schema.properties["recurrence_interval"]!!.jsonObject["type"].toString().trim('"'))
    }
}
