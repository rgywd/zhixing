package me.rerere.rikkahub.data.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

data class AssistantTaskStep(
    val toolName: String,
    val toolCallId: String,
    val input: String,
    val ordinal: Int,
    val requiresUserAnswer: Boolean,
    val hasUserAnswer: Boolean,
)

internal fun AssistantTaskStep.requiresVisibleTask(json: Json): Boolean {
    if (ordinal >= 2) return true
    return requiresDurableTask(json)
}

internal fun AssistantTaskStep.requiresDurableTask(json: Json): Boolean {
    if (requiresUserAnswer) return true
    if (toolName.startsWith("mcp__")) return true
    if (toolName in ALWAYS_TRACKED_TOOLS) return true
    val action = runCatching {
        json.parseToJsonElement(input.ifBlank { "{}" })
            .jsonObject["action"]?.jsonPrimitive?.content
    }.getOrNull()
    return when (toolName) {
        "memory_tool", "memory_write" -> action != null
        "monthly_spending_summary" -> action in setOf("save", "delete")
        else -> false
    }
}

internal fun AssistantTaskStep.progressText(): String = when {
    requiresUserAnswer && !hasUserAnswer -> "需要你补充信息"
    requiresUserAnswer -> "已收到补充信息"
    toolName == "search_web" -> "正在查找资料"
    toolName.contains("location") || toolName == "search_nearby" -> "正在核对位置与出行信息"
    toolName.startsWith("task_") || toolName.startsWith("plan_") -> "正在整理安排"
    toolName.startsWith("calendar_") -> "正在更新日程"
    toolName.startsWith("knowledge_") -> "正在整理知识资料"
    toolName == "gh" -> "正在处理 GitHub 事项"
    toolName.startsWith("mcp__") -> "正在连接外部能力"
    toolName == "monthly_spending_summary" -> "正在整理账单"
    toolName == "memory_tool" || toolName == "memory_write" -> "正在整理你的记忆"
    toolName == "memory_read" -> "正在读取相关记忆"
    toolName.startsWith("workspace_") -> "正在处理工作区内容"
    toolName.contains("inbox") -> "正在查看信息更新"
    else -> "正在继续处理"
}

private val ALWAYS_TRACKED_TOOLS = setOf(
    "task_create",
    "task_update",
    "task_complete",
    "task_delete",
    "plan_create",
    "plan_update",
    "plan_stage_update",
    "plan_stage_complete",
    "plan_set_status",
    "calendar_create",
    "knowledge_ingest",
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_shell",
    "open_navigation",
    "gh",
)

data class AssistantTaskResultLink(
    val objectType: String,
    val objectId: String,
    val role: String = "RESULT",
)

/**
 * A user-facing result derived from a successful Agenda write. The tool output remains unchanged
 * so later model steps can still use its stable machine identifiers.
 */
internal data class AssistantTaskResultPresentation(
    val message: String,
    val title: String? = null,
    /** Values emitted by this Agenda result that must stay in tool JSON, not assistant prose. */
    val technicalValues: Set<String> = emptySet(),
)

data class AssistantTaskFailure(
    val code: String,
    val resultMayBeUnknown: Boolean,
)

internal fun extractAssistantTaskFailure(
    messages: List<UIMessage>,
    json: Json,
): AssistantTaskFailure? = messages
    .flatMap(UIMessage::parts)
    .filterIsInstance<UIMessagePart.Tool>()
    .filter(UIMessagePart.Tool::isExecuted)
    .mapNotNull { tool ->
        val output = tool.output.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n", transform = UIMessagePart.Text::text)
        val value = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull()
            ?: return@mapNotNull null
        val rawError = value["error"] ?: return@mapNotNull null
        val code = when (rawError) {
            is JsonObject -> rawError.string("code") ?: "TOOL_FAILED"
            else -> runCatching { rawError.jsonPrimitive.content }.getOrNull()
                ?.substringAfter('[')
                ?.substringBefore(']')
                ?: "TOOL_FAILED"
        }
        AssistantTaskFailure(
            code = sanitizeCode(code),
            resultMayBeUnknown = tool.toolName == "gh" || tool.toolName.startsWith("mcp__"),
        )
    }
    .firstOrNull()

internal fun extractAssistantTaskResultLinks(
    messages: List<UIMessage>,
    json: Json,
): List<AssistantTaskResultLink> = messages
    .flatMap(UIMessage::parts)
    .filterIsInstance<UIMessagePart.Tool>()
    .filter(UIMessagePart.Tool::isExecuted)
    .flatMap { tool ->
        val output = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n", transform = UIMessagePart.Text::text)
        val value = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull()
        when {
            tool.toolName.startsWith("task_") -> value.findNestedId("task")
                ?.let { listOf(AssistantTaskResultLink("AGENDA_TASK", it)) }
                ?: value?.string("deleted_id")?.let { listOf(AssistantTaskResultLink("AGENDA_TASK", it, "DELETED")) }
                .orEmpty()

            tool.toolName.startsWith("plan_") -> value.findNestedId("plan")
                ?.let { listOf(AssistantTaskResultLink("AGENDA_PLAN", it)) }
                .orEmpty()

            tool.toolName == "knowledge_ingest" -> listOfNotNull(
                value?.string("source_path"),
                value?.string("path"),
                value?.string("normalized_path"),
            ).firstOrNull()?.let { listOf(AssistantTaskResultLink("KNOWLEDGE", it)) }.orEmpty()

            tool.toolName == "gh" -> HTTPS_URL.find(output)?.value
                ?.let { listOf(AssistantTaskResultLink("GITHUB", it)) }
                .orEmpty()

            tool.toolName.startsWith("mcp__") ->
                listOf(AssistantTaskResultLink("MCP_TOOL", tool.toolName, "CAPABILITY"))

            tool.toolName == "monthly_spending_summary" -> value?.string("month")
                ?.let { listOf(AssistantTaskResultLink("MONTHLY_LEDGER", it)) }
                .orEmpty()

            tool.toolName == "memory_tool" -> value.findNestedId("memory")
                ?.let { listOf(AssistantTaskResultLink("MEMORY", it)) }
                .orEmpty()

            tool.toolName == "memory_write" -> value?.string("path")
                ?.let { listOf(AssistantTaskResultLink("MEMORY_DOCUMENT", it)) }
                .orEmpty()

            else -> emptyList()
        }
    }
    .distinctBy { Triple(it.objectType, it.objectId, it.role) }

/**
 * Agenda tools intentionally return complete structured objects to the model. Do not make that
 * object the user-visible completion message: IDs and persistence enums are implementation
 * details, not task outcomes.
 */
internal fun extractAssistantTaskResultPresentation(
    messages: List<UIMessage>,
    json: Json,
    previousSuccessfulAgendaToolCallIds: Set<String> = emptySet(),
): AssistantTaskResultPresentation? = messages
    .flatMap(UIMessage::parts)
    .filterIsInstance<UIMessagePart.Tool>()
    .filter(UIMessagePart.Tool::isExecuted)
    .filter { it.toolCallId !in previousSuccessfulAgendaToolCallIds }
    .mapNotNull { tool ->
        val output = tool.output.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n", transform = UIMessagePart.Text::text)
        val value = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull()
            ?: return@mapNotNull null
        if (value["success"]?.jsonPrimitive?.booleanOrNull != true) return@mapNotNull null
        when (tool.toolName) {
            "task_create" -> value.objectTitle("task")?.let { title ->
                value.agendaPresentation("已创建待办「$title」。", title)
            }

            "task_update" -> value.objectTitle("task")?.let { title ->
                value.agendaPresentation("已更新待办「$title」。", title)
            }

            "task_complete" -> value.objectTitle("task")?.let { title ->
                val completed = runCatching {
                    json.parseToJsonElement(tool.input.ifBlank { "{}" })
                        .jsonObject["completed"]?.jsonPrimitive?.booleanOrNull ?: true
                }.getOrDefault(true)
                value.agendaPresentation(
                    if (completed) "已完成待办「$title」。" else "已恢复待办「$title」。",
                    title,
                )
            }

            "task_delete" -> value.agendaPresentation("已删除待办。")
            "plan_create" -> value.objectTitle("plan")?.let { title ->
                value.agendaPresentation("已创建计划「$title」。", title)
            }

            "plan_update" -> value.objectTitle("plan")?.let { title ->
                value.agendaPresentation("已更新计划「$title」。", title)
            }

            "plan_stage_update", "plan_stage_complete", "plan_set_status" -> value.objectTitle("plan")?.let { title ->
                value.agendaPresentation("已更新计划「$title」。", title)
            }

            else -> null
        }
    }
    .lastOrNull()

internal fun successfulAgendaPresentationToolCallIds(
    messages: List<UIMessage>,
    json: Json,
): Set<String> = messages
    .flatMap(UIMessage::parts)
    .filterIsInstance<UIMessagePart.Tool>()
    .filter(UIMessagePart.Tool::isExecuted)
    .filter { it.toolName in AGENDA_PRESENTATION_TOOLS }
    .filter { tool ->
        val output = tool.output.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n", transform = UIMessagePart.Text::text)
        runCatching { json.parseToJsonElement(output).jsonObject }
            .getOrNull()
            ?.get("success")
            ?.jsonPrimitive
            ?.booleanOrNull == true
    }
    .map(UIMessagePart.Tool::toolCallId)
    .toSet()

/**
 * Keep the model's other user-facing results, but replace only lines that repeat values from the
 * successful Agenda JSON. Tool parts retain their complete machine JSON for later model steps.
 */
internal fun applyAssistantTaskResultPresentation(
    messages: List<UIMessage>,
    presentation: AssistantTaskResultPresentation,
): List<UIMessage> {
    val index = messages.indexOfLast { message ->
        message.role == MessageRole.ASSISTANT && message.parts.any { it is UIMessagePart.Text }
    }
    if (index < 0) return messages
    return messages.mapIndexed { messageIndex, message ->
        if (messageIndex != index) {
            message
        } else {
            var presentationInserted = false
            message.copy(
                parts = buildList {
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                val visibleLines = part.text.lineSequence().mapNotNull { line ->
                                    when {
                                        line.trim() == presentation.message -> {
                                            if (presentationInserted) null else {
                                                presentationInserted = true
                                                presentation.message
                                            }
                                        }

                                        line.containsAgendaTechnicalValue(presentation.technicalValues) -> {
                                            if (presentationInserted) null else {
                                                presentationInserted = true
                                                presentation.message
                                            }
                                        }

                                        else -> line
                                    }
                                }.toList()
                                if (visibleLines.isNotEmpty()) {
                                    add(part.copy(text = visibleLines.joinToString("\n")))
                                }
                            }

                            else -> add(part)
                        }
                    }
                    if (!presentationInserted) {
                        add(UIMessagePart.Text(presentation.message))
                    }
                },
            )
        }
    }
}

private fun JsonObject.agendaPresentation(
    message: String,
    title: String? = null,
): AssistantTaskResultPresentation = AssistantTaskResultPresentation(
    message = message,
    title = title,
    technicalValues = agendaTechnicalValues(),
)

private fun JsonObject.agendaTechnicalValues(): Set<String> = buildSet {
    fun collect(value: JsonElement) {
        when (value) {
            is JsonObject -> value.forEach { (key, nested) ->
                if (key in AGENDA_TECHNICAL_FIELDS) {
                    (nested as? JsonPrimitive)?.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let(::add)
                }
                collect(nested)
            }

            is JsonArray -> value.forEach(::collect)
            else -> Unit
        }
    }
    collect(this@agendaTechnicalValues)
}

private fun String.containsAgendaTechnicalValue(values: Set<String>): Boolean =
    values.any { value -> value.isNotEmpty() && contains(value) }

private fun JsonObject?.findNestedId(container: String): String? {
    val nested = this?.get(container) as? JsonObject ?: return null
    return nested.string("id") ?: (nested[container] as? JsonObject)?.string("id")
}

private fun JsonObject.objectTitle(container: String): String? =
    (this[container] as? JsonObject)?.string("title")

private val AGENDA_PRESENTATION_TOOLS = setOf(
    "task_create",
    "task_update",
    "task_complete",
    "task_delete",
    "plan_create",
    "plan_update",
    "plan_stage_update",
    "plan_stage_complete",
    "plan_set_status",
)

private val AGENDA_TECHNICAL_FIELDS = setOf(
    "id",
    "deleted_id",
    "status",
    "source",
    "recurrence_frequency",
)

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private val HTTPS_URL = Regex("https://[^\\s\\\"'<>]+")
