package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.agenda.AgendaReminderScheduler
import me.rerere.rikkahub.data.db.dao.AgendaTaskDAO
import me.rerere.rikkahub.data.db.entity.toAgendaTask
import me.rerere.rikkahub.data.db.entity.toEntity
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskSource
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import java.util.UUID

class AgendaTaskRepository(
    private val dao: AgendaTaskDAO,
    private val reminderScheduler: AgendaReminderScheduler,
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
    ): AgendaTask {
        require(title.isNotBlank()) { "待办标题不能为空" }
        val now = System.currentTimeMillis()
        require(reminderAt == null || reminderAt > now) { "提醒时间必须晚于当前时间" }
        val task = AgendaTask(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            note = note.trim(),
            status = AgendaTaskStatus.PENDING,
            dueAt = dueAt,
            reminderAt = reminderAt,
            source = source,
            conversationId = conversationId,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )
        dao.upsert(task.toEntity())
        reminderScheduler.sync(task)
        return task
    }

    suspend fun update(
        id: String,
        title: String,
        note: String,
        dueAt: Long?,
        reminderAt: Long?,
    ): AgendaTask {
        require(title.isNotBlank()) { "待办标题不能为空" }
        require(reminderAt == null || reminderAt > System.currentTimeMillis()) { "提醒时间必须晚于当前时间" }
        val old = getById(id) ?: error("待办不存在")
        val updated = old.copy(
            title = title.trim(),
            note = note.trim(),
            dueAt = dueAt,
            reminderAt = reminderAt,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(updated.toEntity())
        reminderScheduler.sync(updated)
        return updated
    }

    suspend fun setCompleted(id: String, completed: Boolean): AgendaTask {
        val old = getById(id) ?: error("待办不存在")
        val now = System.currentTimeMillis()
        val updated = old.copy(
            status = if (completed) AgendaTaskStatus.COMPLETED else AgendaTaskStatus.PENDING,
            reminderAt = if (completed) old.reminderAt else old.reminderAt?.takeIf { it > now },
            completedAt = if (completed) now else null,
            updatedAt = now,
        )
        dao.upsert(updated.toEntity())
        reminderScheduler.sync(updated)
        return updated
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        reminderScheduler.cancel(id)
    }
}
