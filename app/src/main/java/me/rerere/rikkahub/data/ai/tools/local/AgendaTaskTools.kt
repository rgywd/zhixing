package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agenda.parseAgendaTime
import me.rerere.rikkahub.data.model.AgendaRecurrence
import me.rerere.rikkahub.data.model.AgendaRecurrenceFrequency
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskSource
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import me.rerere.rikkahub.data.model.MAX_AGENDA_RECURRENCE_INTERVAL
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import java.time.Instant
import java.time.ZoneId

internal fun buildAgendaTaskTools(
    repository: AgendaTaskRepository,
    conversationId: String? = null,
): List<Tool> = listOf(
    buildTaskListTool(repository),
    buildTaskCreateTool(repository, conversationId),
    buildTaskUpdateTool(repository),
    buildTaskCompleteTool(repository),
    buildTaskDeleteTool(repository),
)

private fun buildTaskListTool(repository: AgendaTaskRepository) = Tool(
    name = "task_list",
    description = "List the user's local Zhixing tasks. Use this before updating an existing task when its id is unknown.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val tasks = repository.getVisibleTasks()
        toolResult {
            put("count", tasks.size)
            put("tasks", buildJsonArray { tasks.forEach { add(it.toJson()) } })
        }
    },
)

private fun buildTaskCreateTool(
    repository: AgendaTaskRepository,
    conversationId: String?,
) = Tool(
    name = "task_create",
    description = "Create a local task or reminder in Zhixing. Use ISO-8601 times or yyyy-MM-dd HH:mm in the device timezone.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                stringProperty("title", "Required task title.")
                stringProperty("note", "Optional task note.")
                stringProperty("due_at", "Optional due time.")
                stringProperty("reminder_at", "Optional notification time. Usually equal to or earlier than due_at.")
                recurrenceProperties()
            },
            required = listOf("title"),
        )
    },
    execute = { args ->
        val input = args.jsonObject
        val title = input.string("title")
        if (title.isBlank()) return@Tool toolError("MISSING_TITLE", "title is required")
        runCatching {
            repository.create(
                title = title,
                note = input.string("note"),
                dueAt = input.optionalTime("due_at"),
                reminderAt = input.optionalTime("reminder_at"),
                source = AgendaTaskSource.CHAT,
                conversationId = conversationId,
                recurrence = input.recurrenceOrExisting(null),
            )
        }.fold(
            onSuccess = { task -> toolResult { put("success", true); put("task", task.toJson()) } },
            onFailure = { toolError("INVALID_TASK", it.message ?: "Unable to create task") },
        )
    },
)

private fun buildTaskUpdateTool(repository: AgendaTaskRepository) = Tool(
    name = "task_update",
    description = "Update a local Zhixing task. Omit a field to keep it unchanged; pass an empty due_at/reminder_at to clear it.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                stringProperty("id", "Required task id from task_list.")
                stringProperty("title", "Optional replacement title.")
                stringProperty("note", "Optional replacement note.")
                stringProperty("due_at", "Optional replacement due time; empty clears it.")
                stringProperty("reminder_at", "Optional replacement reminder time; empty clears it.")
                recurrenceProperties(
                    frequencyDescription = "Optional recurrence frequency. Omit to keep it; empty clears recurrence.",
                    intervalDescription = "Optional replacement recurrence interval.",
                )
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val input = args.jsonObject
        val id = input.string("id")
        val old = repository.getById(id) ?: return@Tool toolError("NOT_FOUND", "Task '$id' was not found")
        runCatching {
            repository.update(
                id = id,
                title = input["title"]?.jsonPrimitive?.contentOrNull ?: old.title,
                note = input["note"]?.jsonPrimitive?.contentOrNull ?: old.note,
                dueAt = input.timeOrExisting("due_at", old.dueAt),
                reminderAt = input.timeOrExisting("reminder_at", old.reminderAt),
                recurrence = input.recurrenceOrExisting(old.recurrence),
            )
        }.fold(
            onSuccess = { task -> toolResult { put("success", true); put("task", task.toJson()) } },
            onFailure = { toolError("INVALID_TASK", it.message ?: "Unable to update task") },
        )
    },
)

private fun buildTaskCompleteTool(repository: AgendaTaskRepository) = Tool(
    name = "task_complete",
    description = "Mark a local Zhixing task completed, or restore it to pending.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                stringProperty("id", "Required task id from task_list.")
                put("completed", buildJsonObject { put("type", "boolean"); put("description", "Default true.") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val input = args.jsonObject
        val completed = input["completed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        runCatching { repository.setCompleted(input.string("id"), completed) }.fold(
            onSuccess = { task -> toolResult { put("success", true); put("task", task.toJson()) } },
            onFailure = { toolError("NOT_FOUND", it.message ?: "Task was not found") },
        )
    },
)

private fun buildTaskDeleteTool(repository: AgendaTaskRepository) = Tool(
    name = "task_delete",
    description = "Permanently delete a local Zhixing task.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { stringProperty("id", "Required task id from task_list.") },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject.string("id")
        if (repository.getById(id) == null) return@Tool toolError("NOT_FOUND", "Task '$id' was not found")
        repository.delete(id)
        toolResult { put("success", true); put("deleted_id", id) }
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

private fun kotlinx.serialization.json.JsonObject.optionalTime(name: String): Long? =
    string(name).takeIf { it.isNotBlank() }?.let(::parseAgendaTime)

private fun kotlinx.serialization.json.JsonObject.timeOrExisting(name: String, old: Long?): Long? {
    if (!containsKey(name)) return old
    return optionalTime(name)
}

private fun kotlinx.serialization.json.JsonObject.recurrenceOrExisting(
    old: AgendaRecurrence?,
): AgendaRecurrence? {
    val hasFrequency = containsKey("recurrence_frequency")
    val hasInterval = containsKey("recurrence_interval")
    if (!hasFrequency && !hasInterval) return old

    val rawFrequency = this["recurrence_frequency"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (hasFrequency && rawFrequency.isEmpty()) return null
    val frequency = if (hasFrequency) {
        runCatching { AgendaRecurrenceFrequency.valueOf(rawFrequency.uppercase()) }
            .getOrElse { error("recurrence_frequency must be DAILY, WEEKLY, or MONTHLY") }
    } else {
        old?.frequency ?: error("recurrence_interval requires recurrence_frequency")
    }
    val interval = if (hasInterval) {
        this["recurrence_interval"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: error("recurrence_interval must be an integer")
    } else if (hasFrequency && old?.frequency != frequency) {
        1
    } else {
        old?.interval ?: 1
    }
    return AgendaRecurrence(frequency, interval)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, description: String) {
    put(name, buildJsonObject { put("type", "string"); put("description", description) })
}

private fun kotlinx.serialization.json.JsonObjectBuilder.recurrenceProperties(
    frequencyDescription: String = "Optional recurrence frequency. Requires due_at or reminder_at.",
    intervalDescription: String = "Optional recurrence interval. Defaults to 1.",
) {
    put("recurrence_frequency", buildJsonObject {
        put("type", "string")
        put("description", frequencyDescription)
        put("enum", buildJsonArray {
            AgendaRecurrenceFrequency.entries.forEach { add(it.name) }
        })
    })
    put("recurrence_interval", buildJsonObject {
        put("type", "integer")
        put("description", intervalDescription)
        put("minimum", 1)
        put("maximum", MAX_AGENDA_RECURRENCE_INTERVAL)
    })
}

private fun AgendaTask.toJson() = buildJsonObject {
    put("id", id)
    put("title", title)
    put("note", note)
    put("status", status.name)
    put(
        "due_at",
        dueAt?.let { JsonPrimitive(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toString()) } ?: JsonNull,
    )
    put(
        "reminder_at",
        reminderAt?.let { JsonPrimitive(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toString()) } ?: JsonNull,
    )
    put("source", source.name)
    put("completed", status == AgendaTaskStatus.COMPLETED)
    put("recurrence_frequency", recurrence?.frequency?.name?.let(::JsonPrimitive) ?: JsonNull)
    put("recurrence_interval", recurrence?.interval?.let(::JsonPrimitive) ?: JsonNull)
}

private fun toolResult(content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
    listOf(UIMessagePart.Text(buildJsonObject(content).toString()))

private fun toolError(code: String, message: String) = toolResult {
    put("error", code)
    put("message", message)
}
