package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agenda.currentAgendaPlanStage
import me.rerere.rikkahub.data.agenda.parseAgendaTime
import me.rerere.rikkahub.data.model.AgendaPlanSource
import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStageDraft
import me.rerere.rikkahub.data.model.AgendaPlanStatus
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import java.time.Instant
import java.time.ZoneId

internal fun buildAgendaPlanTools(repository: AgendaPlanRepository): List<Tool> = listOf(
    buildPlanListTool(repository),
    buildPlanGetTool(repository),
    buildPlanCreateTool(repository),
    buildPlanUpdateTool(repository),
    buildPlanStageUpdateTool(repository),
    buildPlanStageCompleteTool(repository),
    buildPlanSetStatusTool(repository),
)

private fun buildPlanListTool(repository: AgendaPlanRepository) = Tool(
    name = "plan_list",
    description = "List local long-horizon plans as compact summaries. Use plan_get for the full stage timeline.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val plans = repository.getVisiblePlans()
        planToolResult {
            put("count", plans.size)
            put("plans", buildJsonArray { plans.forEach { add(it.toSummaryJson()) } })
        }
    },
)

private fun buildPlanGetTool(repository: AgendaPlanRepository) = Tool(
    name = "plan_get",
    description = "Read one local long-horizon plan and its ordered stages.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { planStringProperty("id", "Required plan id from plan_list.") },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject.planString("id")
        val plan = repository.getById(id) ?: return@Tool planToolError("NOT_FOUND", "Plan '$id' was not found")
        planToolResult { put("plan", plan.toDetailJson()) }
    },
)

private fun buildPlanCreateTool(repository: AgendaPlanRepository) = Tool(
    name = "plan_create",
    description = """
        Create one local long-horizon plan and all of its ordered stages in a single approved write.
        Use absolute ISO-8601/device-local times or *_offset_minutes relative to event_at; never provide both forms
        for the same field. Negative offsets mean before the event, for example -21600 is T-15 days.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                planStringProperty("title", "Required plan title.")
                planStringProperty("note", "Optional plan note.")
                planStringProperty("location", "Optional place or semantic location.")
                planStringProperty("event_at", "Required parent event time.")
                planStringProperty("source_reference", "Optional auditable source message or external reference.")
                put("stages", stageArraySchema())
            },
            required = listOf("title", "event_at", "stages"),
        )
    },
    execute = { args ->
        val input = args.jsonObject
        val title = input.planString("title")
        val eventAtText = input.planString("event_at")
        val stageArray = input["stages"] as? JsonArray
            ?: return@Tool planToolError("INVALID_STAGES", "stages must be an array")
        if (title.isBlank()) return@Tool planToolError("MISSING_TITLE", "title is required")
        if (eventAtText.isBlank()) return@Tool planToolError("MISSING_EVENT_AT", "event_at is required")
        if (stageArray.isEmpty()) return@Tool planToolError("INVALID_STAGES", "at least one stage is required")
        runCatching {
            repository.create(
                title = title,
                note = input.planString("note"),
                location = input.planString("location"),
                eventAt = parseAgendaTime(eventAtText),
                stages = stageArray.map { it.jsonObject.toDraft() },
                source = AgendaPlanSource.CHAT,
                sourceReference = input.planString("source_reference").takeIf { it.isNotBlank() },
            )
        }.fold(
            onSuccess = { plan -> planToolResult { put("success", true); put("plan", plan.toDetailJson()) } },
            onFailure = { planToolError("INVALID_PLAN", it.message ?: "Unable to create plan") },
        )
    },
)

private fun buildPlanUpdateTool(repository: AgendaPlanRepository) = Tool(
    name = "plan_update",
    description = """
        Update a local long-horizon plan. Moving event_at automatically recalculates stage fields that use relative
        offsets while preserving absolute stage times. Omit a field to keep it; empty event_at clears it when possible.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                planStringProperty("id", "Required plan id.")
                planStringProperty("title", "Optional replacement title.")
                planStringProperty("note", "Optional replacement note.")
                planStringProperty("location", "Optional replacement location.")
                planStringProperty("event_at", "Optional replacement event time; empty clears it.")
                planStringProperty("source_reference", "Optional replacement source reference.")
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val input = args.jsonObject
        val id = input.planString("id")
        val old = repository.getById(id) ?: return@Tool planToolError("NOT_FOUND", "Plan '$id' was not found")
        runCatching {
            repository.updatePlan(
                id = id,
                title = input.valueOrOld("title", old.plan.title),
                note = input.valueOrOld("note", old.plan.note),
                location = input.valueOrOld("location", old.plan.location),
                eventAt = input.timeOrExisting("event_at", old.plan.eventAt),
                sourceReference = input.nullableValueOrOld("source_reference", old.plan.sourceReference),
            )
        }.fold(
            onSuccess = { plan -> planToolResult { put("success", true); put("plan", plan.toDetailJson()) } },
            onFailure = { planToolError("INVALID_PLAN", it.message ?: "Unable to update plan") },
        )
    },
)

private fun buildPlanStageUpdateTool(repository: AgendaPlanRepository) = Tool(
    name = "plan_stage_update",
    description = """
        Update one plan stage while keeping its order and completion state. Omitted fields keep their current values.
        For a time field, use either the absolute value or its *_offset_minutes counterpart.
    """.trimIndent().replace("\n", " "),
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                planStringProperty("id", "Required stage id from plan_get.")
                stageProperties().forEach { (name, schema) -> put(name, schema) }
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val input = args.jsonObject
        val id = input.planString("id")
        val old = repository.getStageById(id) ?: return@Tool planToolError("NOT_FOUND", "Stage '$id' was not found")
        runCatching {
            repository.updateStage(id, input.toDraft(old))
        }.fold(
            onSuccess = { plan -> planToolResult { put("success", true); put("plan", plan.toDetailJson()) } },
            onFailure = { planToolError("INVALID_STAGE", it.message ?: "Unable to update stage") },
        )
    },
)

private fun buildPlanStageCompleteTool(repository: AgendaPlanRepository) = Tool(
    name = "plan_stage_complete",
    description = "Complete or restore one plan stage. Completing a stage never completes the parent plan.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                planStringProperty("id", "Required stage id from plan_get.")
                put("completed", buildJsonObject { put("type", "boolean"); put("description", "Default true.") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val input = args.jsonObject
        val completed = input["completed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        runCatching { repository.setStageCompleted(input.planString("id"), completed) }.fold(
            onSuccess = { plan -> planToolResult { put("success", true); put("plan", plan.toDetailJson()) } },
            onFailure = { planToolError("NOT_FOUND", it.message ?: "Stage was not found") },
        )
    },
)

private fun buildPlanSetStatusTool(repository: AgendaPlanRepository) = Tool(
    name = "plan_set_status",
    description = "Set a parent plan ACTIVE, COMPLETED, or CANCELLED. This is separate from completing a stage.",
    needsApproval = { true },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                planStringProperty("id", "Required plan id.")
                put("status", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("ACTIVE")
                        add("COMPLETED")
                        add("CANCELLED")
                    })
                })
            },
            required = listOf("id", "status"),
        )
    },
    execute = { args ->
        val input = args.jsonObject
        val status = runCatching { AgendaPlanStatus.valueOf(input.planString("status").uppercase()) }
            .getOrElse { return@Tool planToolError("INVALID_STATUS", "status must be ACTIVE, COMPLETED, or CANCELLED") }
        runCatching { repository.setPlanStatus(input.planString("id"), status) }.fold(
            onSuccess = { plan -> planToolResult { put("success", true); put("plan", plan.toDetailJson()) } },
            onFailure = { planToolError("NOT_FOUND", it.message ?: "Plan was not found") },
        )
    },
)

private fun stageArraySchema() = buildJsonObject {
    put("type", "array")
    put("minItems", 1)
    put("description", "Ordered preparation or follow-up stages.")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", stageProperties())
        put("required", buildJsonArray { add("title") })
    })
}

private fun stageProperties(): JsonObject = buildJsonObject {
    planStringProperty("title", "Required stage title.")
    planStringProperty("note", "Optional stage note.")
    planStringProperty("scheduled_at", "Optional planned execution time.")
    planStringProperty("due_at", "Optional latest completion time.")
    planStringProperty("trigger_at", "Optional time when this stage becomes actionable.")
    planStringProperty("reminder_at", "Optional notification time.")
    planIntegerProperty("scheduled_offset_minutes", "Optional scheduled_at offset from event_at.")
    planIntegerProperty("due_offset_minutes", "Optional due_at offset from event_at.")
    planIntegerProperty("trigger_offset_minutes", "Optional trigger_at offset from event_at.")
    planIntegerProperty("reminder_offset_minutes", "Optional reminder_at offset from event_at.")
}

private fun JsonObject.toDraft(old: AgendaPlanStage? = null) = AgendaPlanStageDraft(
    title = valueOrOld("title", old?.title.orEmpty()),
    note = valueOrOld("note", old?.note.orEmpty()),
    scheduledAt = absoluteOrExisting("scheduled_at", "scheduled_offset_minutes", old?.scheduledAt),
    dueAt = absoluteOrExisting("due_at", "due_offset_minutes", old?.dueAt),
    triggerAt = absoluteOrExisting("trigger_at", "trigger_offset_minutes", old?.triggerAt),
    reminderAt = absoluteOrExisting("reminder_at", "reminder_offset_minutes", old?.reminderAt),
    scheduledOffsetMinutes = offsetOrExisting("scheduled_offset_minutes", "scheduled_at", old?.scheduledOffsetMinutes),
    dueOffsetMinutes = offsetOrExisting("due_offset_minutes", "due_at", old?.dueOffsetMinutes),
    triggerOffsetMinutes = offsetOrExisting("trigger_offset_minutes", "trigger_at", old?.triggerOffsetMinutes),
    reminderOffsetMinutes = offsetOrExisting("reminder_offset_minutes", "reminder_at", old?.reminderOffsetMinutes),
)

private fun AgendaPlanWithStages.toSummaryJson() = buildJsonObject {
    val current = currentAgendaPlanStage(this@toSummaryJson)
    put("id", plan.id)
    put("title", plan.title)
    put("status", plan.status.name)
    putTime("event_at", plan.eventAt)
    put("completed_stage_count", stages.count { it.status.name == "COMPLETED" })
    put("stage_count", stages.size)
    put("next_stage", current?.let { JsonPrimitive(it.title) } ?: JsonNull)
}

private fun AgendaPlanWithStages.toDetailJson() = buildJsonObject {
    put("id", plan.id)
    put("title", plan.title)
    put("note", plan.note)
    put("location", plan.location)
    put("status", plan.status.name)
    putTime("event_at", plan.eventAt)
    put("source", plan.source.name)
    put("source_reference", plan.sourceReference?.let(::JsonPrimitive) ?: JsonNull)
    put("stages", buildJsonArray { stages.sortedBy { it.position }.forEach { add(it.toJson()) } })
}

private fun AgendaPlanStage.toJson() = buildJsonObject {
    put("id", id)
    put("title", title)
    put("note", note)
    put("status", status.name)
    put("position", position)
    putTime("scheduled_at", scheduledAt)
    putTime("due_at", dueAt)
    putTime("trigger_at", triggerAt)
    putTime("reminder_at", reminderAt)
    putNullableLong("scheduled_offset_minutes", scheduledOffsetMinutes)
    putNullableLong("due_offset_minutes", dueOffsetMinutes)
    putNullableLong("trigger_offset_minutes", triggerOffsetMinutes)
    putNullableLong("reminder_offset_minutes", reminderOffsetMinutes)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putTime(name: String, value: Long?) {
    put(
        name,
        value?.let { JsonPrimitive(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toString()) } ?: JsonNull,
    )
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(name: String, value: Long?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObject.planString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

private fun JsonObject.valueOrOld(name: String, old: String): String =
    if (containsKey(name)) planString(name) else old

private fun JsonObject.nullableValueOrOld(name: String, old: String?): String? =
    if (containsKey(name)) planString(name).takeIf { it.isNotBlank() } else old

private fun JsonObject.timeOrExisting(name: String, old: Long?): Long? {
    if (!containsKey(name)) return old
    return planString(name).takeIf { it.isNotBlank() }?.let(::parseAgendaTime)
}

private fun JsonObject.absoluteOrExisting(name: String, offsetName: String, old: Long?): Long? {
    if (containsKey(name)) return planString(name).takeIf { it.isNotBlank() }?.let(::parseAgendaTime)
    if (containsKey(offsetName)) return null
    return old
}

private fun JsonObject.offsetOrExisting(name: String, absoluteName: String, old: Long?): Long? {
    if (!containsKey(name)) return if (containsKey(absoluteName)) null else old
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    return element.jsonPrimitive.longOrNull
        ?: element.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.toLong()
}

private fun kotlinx.serialization.json.JsonObjectBuilder.planStringProperty(name: String, description: String) {
    put(name, buildJsonObject { put("type", "string"); put("description", description) })
}

private fun kotlinx.serialization.json.JsonObjectBuilder.planIntegerProperty(name: String, description: String) {
    put(name, buildJsonObject { put("type", "integer"); put("description", description) })
}

private fun planToolResult(content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
    listOf(UIMessagePart.Text(buildJsonObject(content).toString()))

private fun planToolError(code: String, message: String) = planToolResult {
    put("error", code)
    put("message", message)
}
