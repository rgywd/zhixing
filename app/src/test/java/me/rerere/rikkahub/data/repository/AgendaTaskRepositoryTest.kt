package me.rerere.rikkahub.data.repository

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.agenda.AgendaTaskReminderGateway
import me.rerere.rikkahub.data.db.dao.AgendaTaskDAO
import me.rerere.rikkahub.data.db.entity.AgendaTaskEntity
import me.rerere.rikkahub.data.model.AgendaRecurrence
import me.rerere.rikkahub.data.model.AgendaRecurrenceFrequency
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgendaTaskRepositoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private var clock = ZonedDateTime.of(2026, 8, 1, 7, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `completing one recurring occurrence advances the series and keeps it pending`() = runBlocking {
        val dao = FakeAgendaTaskDao()
        val reminders = FakeAgendaTaskReminderGateway()
        val repository = AgendaTaskRepository(dao, reminders) { clock }
        val dueAt = ZonedDateTime.of(2026, 8, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val created = repository.create(
            title = "晨间简报",
            dueAt = dueAt,
            reminderAt = dueAt,
            recurrence = AgendaRecurrence(AgendaRecurrenceFrequency.DAILY),
        )
        clock = dueAt + 60 * 60 * 1_000L

        val advanced = repository.setCompleted(created.id, true)

        assertEquals(AgendaTaskStatus.PENDING, advanced.status)
        assertEquals(dueAt + 24 * 60 * 60 * 1_000L, advanced.dueAt)
        assertEquals(advanced.dueAt, advanced.reminderAt)
        assertEquals(null, advanced.completedAt)
        assertEquals(listOf(created.id, created.id), reminders.synced.map(AgendaTask::id))
    }

    @Test
    fun `non recurring completion keeps existing completed behavior`() = runBlocking {
        val repository = AgendaTaskRepository(FakeAgendaTaskDao(), FakeAgendaTaskReminderGateway()) { clock }
        val created = repository.create(title = "一次性事项")

        val completed = repository.setCompleted(created.id, true)

        assertEquals(AgendaTaskStatus.COMPLETED, completed.status)
        assertEquals(clock, completed.completedAt)
    }

    @Test
    fun `recurrence without due or reminder is rejected before persistence`() {
        val dao = FakeAgendaTaskDao()
        val repository = AgendaTaskRepository(dao, FakeAgendaTaskReminderGateway()) { clock }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.create(
                    title = "无时间周期事项",
                    recurrence = AgendaRecurrence(AgendaRecurrenceFrequency.WEEKLY),
                )
            }
        }
        assertEquals(0, dao.tasks.size)
    }
}

internal class FakeAgendaTaskReminderGateway : AgendaTaskReminderGateway {
    val synced = mutableListOf<AgendaTask>()
    val cancelled = mutableListOf<String>()

    override fun sync(task: AgendaTask) {
        synced += task
    }

    override fun cancel(taskId: String) {
        cancelled += taskId
    }
}

internal class FakeAgendaTaskDao : AgendaTaskDAO {
    val tasks = linkedMapOf<String, AgendaTaskEntity>()

    override fun observeVisibleTasks(): Flow<List<AgendaTaskEntity>> = flowOf(tasks.values.toList())

    override suspend fun getById(id: String): AgendaTaskEntity? = tasks[id]

    override suspend fun getVisibleTasks(): List<AgendaTaskEntity> =
        tasks.values.filter { it.status != AgendaTaskStatus.CANCELLED.name }

    override suspend fun upsert(task: AgendaTaskEntity) {
        tasks[task.id] = task
    }

    override suspend fun delete(id: String) {
        tasks.remove(id)
    }
}
