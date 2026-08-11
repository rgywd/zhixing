package me.rerere.rikkahub.data.task

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.AssistantTaskDAO
import me.rerere.rikkahub.data.db.entity.AssistantTaskEntity
import me.rerere.rikkahub.data.db.entity.AssistantTaskEventEntity
import me.rerere.rikkahub.data.db.entity.AssistantTaskLinkEntity

enum class AssistantTaskStatus {
    RUNNING,
    WAITING_FOR_INPUT,
    COMPLETED,
    FAILED_RETRYABLE,
    STOPPED,
}

enum class AssistantTaskEventType {
    STARTED,
    PROGRESS,
    WAITING_FOR_INPUT,
    RESUMED,
    COMPLETED,
    FAILED,
    STOPPED,
    LINKED,
}

internal fun canTransitionAssistantTask(
    from: AssistantTaskStatus,
    to: AssistantTaskStatus,
): Boolean = when (from) {
    AssistantTaskStatus.RUNNING -> to in setOf(
        AssistantTaskStatus.WAITING_FOR_INPUT,
        AssistantTaskStatus.COMPLETED,
        AssistantTaskStatus.FAILED_RETRYABLE,
        AssistantTaskStatus.STOPPED,
    )

    AssistantTaskStatus.WAITING_FOR_INPUT -> to in setOf(
        AssistantTaskStatus.RUNNING,
        AssistantTaskStatus.COMPLETED,
        AssistantTaskStatus.FAILED_RETRYABLE,
        AssistantTaskStatus.STOPPED,
    )

    AssistantTaskStatus.FAILED_RETRYABLE -> to == AssistantTaskStatus.RUNNING
    AssistantTaskStatus.COMPLETED,
    AssistantTaskStatus.STOPPED,
    -> false
}

class AssistantTaskRepository(
    private val database: AppDatabase,
    private val dao: AssistantTaskDAO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val writeMutex = Mutex()

    fun observeTasks(): Flow<List<AssistantTaskEntity>> = dao.observeAll()

    fun observeEvents(taskId: String): Flow<List<AssistantTaskEventEntity>> =
        dao.observeEvents(taskId)

    suspend fun findActiveForConversation(conversationId: String): AssistantTaskEntity? =
        dao.findActiveForConversation(conversationId)

    suspend fun retryLatestForConversation(conversationId: String): String? {
        val task = dao.findRetryableForConversation(conversationId) ?: return null
        retry(task.id)
        return task.id
    }

    suspend fun create(
        title: String,
        conversationId: String,
        anchorMessageId: String?,
        anchorNodeId: String?,
    ): AssistantTaskEntity = writeMutex.withLock {
        val now = clock()
        val task = AssistantTaskEntity(
            id = UUID.randomUUID().toString(),
            title = sanitizeUserFacingText(title, fallback = "正在处理你的请求"),
            status = AssistantTaskStatus.RUNNING.name,
            attempt = 1,
            conversationId = conversationId,
            anchorMessageId = anchorMessageId,
            anchorNodeId = anchorNodeId,
            summary = "已开始处理",
            resultKind = null,
            resultRef = null,
            errorCode = null,
            attentionReason = null,
            createdAt = now,
            updatedAt = now,
            startedAt = now,
            finishedAt = null,
        )
        database.withTransaction {
            dao.insertTask(task)
            dao.insertEvent(
                AssistantTaskEventEntity(
                    taskId = task.id,
                    seq = 1,
                    type = AssistantTaskEventType.STARTED.name,
                    message = "开始处理",
                    resultRef = null,
                    errorCode = null,
                    idempotencyKey = "task-started",
                    createdAt = now,
                )
            )
            dao.insertLink(
                AssistantTaskLinkEntity(
                    taskId = task.id,
                    objectType = "CONVERSATION",
                    objectId = conversationId,
                    role = "SOURCE",
                    createdAt = now,
                )
            )
            anchorMessageId?.let { messageId ->
                dao.insertLink(
                    AssistantTaskLinkEntity(
                        taskId = task.id,
                        objectType = "MESSAGE",
                        objectId = messageId,
                        role = "ANCHOR",
                        createdAt = now,
                    )
                )
            }
        }
        task
    }

    suspend fun recordProgress(
        taskId: String,
        message: String,
        idempotencyKey: String? = null,
    ) = appendAndUpdate(
        taskId = taskId,
        type = AssistantTaskEventType.PROGRESS,
        message = message,
        idempotencyKey = idempotencyKey,
    ) { task, now ->
        task.copy(
            summary = sanitizeUserFacingText(message, "正在处理"),
            updatedAt = now,
            attentionReason = null,
        )
    }

    suspend fun waitForInput(taskId: String, message: String) = transition(
        taskId = taskId,
        status = AssistantTaskStatus.WAITING_FOR_INPUT,
        type = AssistantTaskEventType.WAITING_FOR_INPUT,
        message = message,
        attentionReason = "等待你回答",
    )

    suspend fun resume(taskId: String) = appendAndUpdate(
        taskId = taskId,
        type = AssistantTaskEventType.RESUMED,
        message = "已收到回答，继续处理",
    ) { task, now ->
        val from = AssistantTaskStatus.valueOf(task.status)
        require(canTransitionAssistantTask(from, AssistantTaskStatus.RUNNING))
        task.copy(
            status = AssistantTaskStatus.RUNNING.name,
            summary = "已收到回答，继续处理",
            attentionReason = null,
            updatedAt = now,
            finishedAt = null,
        )
    }

    suspend fun complete(
        taskId: String,
        summary: String = "已完成",
        resultKind: String? = null,
        resultRef: String? = null,
    ) = transition(
        taskId = taskId,
        status = AssistantTaskStatus.COMPLETED,
        type = AssistantTaskEventType.COMPLETED,
        message = summary,
        resultKind = resultKind,
        resultRef = resultRef,
    )

    suspend fun fail(taskId: String, errorCode: String, message: String) = transition(
        taskId = taskId,
        status = AssistantTaskStatus.FAILED_RETRYABLE,
        type = AssistantTaskEventType.FAILED,
        message = message,
        errorCode = sanitizeCode(errorCode),
        attentionReason = "可重试",
    )

    suspend fun stop(taskId: String, message: String = "已停止后续步骤") = transition(
        taskId = taskId,
        status = AssistantTaskStatus.STOPPED,
        type = AssistantTaskEventType.STOPPED,
        message = message,
    )

    suspend fun retry(taskId: String) = appendAndUpdate(
        taskId = taskId,
        type = AssistantTaskEventType.RESUMED,
        message = "重新尝试",
    ) { task, now ->
        val from = AssistantTaskStatus.valueOf(task.status)
        require(canTransitionAssistantTask(from, AssistantTaskStatus.RUNNING))
        task.copy(
            status = AssistantTaskStatus.RUNNING.name,
            attempt = task.attempt + 1,
            summary = "重新尝试",
            errorCode = null,
            attentionReason = null,
            updatedAt = now,
            startedAt = now,
            finishedAt = null,
        )
    }

    suspend fun addLink(
        taskId: String,
        objectType: String,
        objectId: String,
        role: String = "RESULT",
    ) = writeMutex.withLock {
        val now = clock()
        database.withTransaction {
            dao.insertLink(
                AssistantTaskLinkEntity(
                    taskId = taskId,
                    objectType = sanitizeCode(objectType),
                    objectId = sanitizeReference(objectId),
                    role = sanitizeCode(role),
                    createdAt = now,
                )
            )
            appendEventLocked(
                taskId = taskId,
                type = AssistantTaskEventType.LINKED,
                message = "已关联结果",
                resultRef = sanitizeReference(objectId),
                errorCode = null,
                idempotencyKey = "link:${sanitizeCode(objectType)}:${sanitizeReference(objectId)}",
                now = now,
            )
        }
    }

    suspend fun updateTitle(taskId: String, title: String) = writeMutex.withLock {
        val now = clock()
        database.withTransaction {
            val task = requireNotNull(dao.getTask(taskId)) { "Assistant task not found" }
            dao.updateTask(
                task.copy(
                    title = sanitizeUserFacingText(title, fallback = task.title),
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun reconcileOnStartup(): Int {
        val runningTasks = dao.getRunningTasks()
        runningTasks.forEach { task ->
            fail(
                taskId = task.id,
                errorCode = "APP_RESTARTED",
                message = "应用重新启动，已保留现有结果，可手动重试",
            )
        }
        return runningTasks.size
    }

    suspend fun invalidateConversationLinks(conversationId: String) =
        dao.invalidateConversationLinks(conversationId)

    private suspend fun transition(
        taskId: String,
        status: AssistantTaskStatus,
        type: AssistantTaskEventType,
        message: String,
        resultKind: String? = null,
        resultRef: String? = null,
        errorCode: String? = null,
        attentionReason: String? = null,
    ) = appendAndUpdate(
        taskId = taskId,
        type = type,
        message = message,
        resultRef = resultRef,
        errorCode = errorCode,
    ) { task, now ->
        val from = AssistantTaskStatus.valueOf(task.status)
        require(canTransitionAssistantTask(from, status)) {
            "Invalid assistant task transition: $from -> $status"
        }
        task.copy(
            status = status.name,
            summary = sanitizeUserFacingText(message, status.name),
            resultKind = resultKind?.let(::sanitizeCode),
            resultRef = resultRef?.let(::sanitizeReference),
            errorCode = errorCode?.let(::sanitizeCode),
            attentionReason = attentionReason,
            updatedAt = now,
            finishedAt = if (status == AssistantTaskStatus.WAITING_FOR_INPUT) null else now,
        )
    }

    private suspend fun appendAndUpdate(
        taskId: String,
        type: AssistantTaskEventType,
        message: String,
        resultRef: String? = null,
        errorCode: String? = null,
        idempotencyKey: String? = null,
        update: (AssistantTaskEntity, Long) -> AssistantTaskEntity,
    ) = writeMutex.withLock {
        val now = clock()
        database.withTransaction {
            val task = requireNotNull(dao.getTask(taskId)) { "Assistant task not found" }
            val inserted = appendEventLocked(
                taskId = taskId,
                type = type,
                message = message,
                resultRef = resultRef,
                errorCode = errorCode,
                idempotencyKey = idempotencyKey,
                now = now,
            )
            if (inserted) dao.updateTask(update(task, now))
        }
    }

    private suspend fun appendEventLocked(
        taskId: String,
        type: AssistantTaskEventType,
        message: String,
        resultRef: String?,
        errorCode: String?,
        idempotencyKey: String?,
        now: Long,
    ): Boolean {
        val event = AssistantTaskEventEntity(
            taskId = taskId,
            seq = dao.maxEventSeq(taskId) + 1,
            type = type.name,
            message = sanitizeUserFacingText(message, type.name),
            resultRef = resultRef?.let(::sanitizeReference),
            errorCode = errorCode?.let(::sanitizeCode),
            idempotencyKey = idempotencyKey?.take(120),
            createdAt = now,
        )
        return dao.insertEvent(event) != -1L
    }
}

internal fun sanitizeUserFacingText(value: String, fallback: String): String = value
    .replace(Regex("(?i)(token|api[_ -]?key|authorization)\\s*[:=]\\s*\\S+")) {
        "${it.groupValues[1]}=[已隐藏]"
    }
    .replace(Regex("[\\r\\n\\t]+"), " ")
    .trim()
    .take(160)
    .ifBlank { fallback }

/**
 * Task cards describe the user's goal, never the model-facing invocation instruction.
 * Keep the natural prefix and drop explicit requests to call snake_case tool names.
 */
internal fun naturalizeAssistantTaskTitle(value: String): String {
    val namedAgendaItem = Regex(
        pattern = "(?i)^\\s*(?:please\\s+)?create\\s+(?:a\\s+)?(?:task|todo|reminder|plan)\\s+" +
            "(?:named|called)\\s+[\\\"“]?(.+?)[\\\"”]?(?:\\s+(?:for|at|on|due)\\b.*)?$",
    ).find(value)?.groupValues?.getOrNull(1)?.trim()
    if (!namedAgendaItem.isNullOrBlank()) {
        return sanitizeUserFacingText(namedAgendaItem, fallback = "正在处理你的请求")
    }
    val withoutInvocation = value.replace(
        Regex(
            pattern = "(?i)[,.]?\\s*(?:now\\s+)?(?:use|call)\\s+(?:the\\s+)?" +
                "(?:ask_user|[a-z][a-z0-9]*_[a-z0-9_]+|mcp__[a-z0-9_]+)\\b.*$",
        ),
        "",
    )
    return sanitizeUserFacingText(withoutInvocation, fallback = "正在处理你的请求")
}

internal fun sanitizeReference(value: String): String = value
    .substringBefore('?')
    .substringBefore('#')
    .replace(Regex("[\\r\\n\\t]+"), "")
    .take(512)

internal fun sanitizeCode(value: String): String = value
    .uppercase()
    .replace(Regex("[^A-Z0-9_.:-]"), "_")
    .take(80)
