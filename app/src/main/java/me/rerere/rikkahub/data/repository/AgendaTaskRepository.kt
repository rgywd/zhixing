package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.agenda.AgendaTaskReminderGateway
import me.rerere.rikkahub.data.agenda.advanceAgendaRecurrence
import me.rerere.rikkahub.data.db.dao.AgendaTaskDAO
import me.rerere.rikkahub.data.db.entity.toAgendaTask
import me.rerere.rikkahub.data.db.entity.toEntity
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaRecurrence
import me.rerere.rikkahub.data.model.AgendaTaskSource
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import java.util.UUID

class AgendaTaskRepository(
    private val dao: AgendaTaskDAO,
    private val reminderGateway: AgendaTaskReminderGateway,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observeVisibleTasks(): Flow<List<AgendaTask>> = dao.observeVisibleTasks().map { rows ->
        rows.map { it.toAgendaTask() }
    }

    suspend fun getVisibleTasks(): List<AgendaTask> = dao.getVisibleTasks().map { it.toAgendaTask() }

    suspend fun getById(id: String): AgendaTask? = dao.getById(id)?.toAgendaTask()

    suspend fun create(
        title: String,
        note: String = "",
        dueAt: Long? = null,
        reminderAt: Long? = null,
        source: AgendaTaskSource = AgendaTaskSource.MANUAL,
        conversationId: String? = null,
        recurrence: AgendaRecurrence? = null,
    ): AgendaTask {
        require(title.isNotBlank()) { "待办标题不能为空" }
        val timestamp = now()
        require(reminderAt == null || reminderAt > timestamp) { "提醒时间必须晚于当前时间" }
        validateRecurrence(recurrence, dueAt, reminderAt)
        val task = AgendaTask(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            note = note.trim(),
            status = AgendaTaskStatus.PENDING,
            dueAt = dueAt,
            reminderAt = reminderAt,
            source = source,
            conversationId = conversationId,
            createdAt = timestamp,
            updatedAt = timestamp,
            completedAt = null,
            recurrence = recurrence,
        )
        dao.upsert(task.toEntity())
        reminderGateway.sync(task)
        return task
    }

    suspend fun update(
        id: String,
        title: String,
        note: String,
        dueAt: Long?,
        reminderAt: Long?,
        recurrence: AgendaRecurrence?,
    ): AgendaTask {
        require(title.isNotBlank()) { "待办标题不能为空" }
        require(reminderAt == null || reminderAt > now()) { "提醒时间必须晚于当前时间" }
        validateRecurrence(recurrence, dueAt, reminderAt)
        val old = getById(id) ?: error("待办不存在")
        val updated = old.copy(
            title = title.trim(),
            note = note.trim(),
            dueAt = dueAt,
            reminderAt = reminderAt,
            recurrence = recurrence,
            updatedAt = now(),
        )
        dao.upsert(updated.toEntity())
        reminderGateway.sync(updated)
        return updated
    }

    suspend fun setCompleted(id: String, completed: Boolean): AgendaTask {
        val old = getById(id) ?: error("待办不存在")
        val timestamp = now()
        val advance = old.recurrence?.takeIf { completed }?.let { recurrence ->
            advanceAgendaRecurrence(recurrence, old.dueAt, old.reminderAt, timestamp)
        }
        val updated = if (advance != null) {
            old.copy(
                status = AgendaTaskStatus.PENDING,
                dueAt = advance.dueAt,
                reminderAt = advance.reminderAt,
                completedAt = null,
                updatedAt = timestamp,
            )
        } else {
            old.copy(
                status = if (completed) AgendaTaskStatus.COMPLETED else AgendaTaskStatus.PENDING,
                reminderAt = if (completed) old.reminderAt else old.reminderAt?.takeIf { it > timestamp },
                completedAt = if (completed) timestamp else null,
                updatedAt = timestamp,
            )
        }
        dao.upsert(updated.toEntity())
        reminderGateway.sync(updated)
        return updated
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        reminderGateway.cancel(id)
    }

    suspend fun reconcileReminders() {
        getVisibleTasks().forEach(reminderGateway::sync)
    }

    private fun validateRecurrence(recurrence: AgendaRecurrence?, dueAt: Long?, reminderAt: Long?) {
        require(recurrence == null || dueAt != null || reminderAt != null) {
            "周期待办必须设置截止时间或提醒时间"
        }
    }
}
