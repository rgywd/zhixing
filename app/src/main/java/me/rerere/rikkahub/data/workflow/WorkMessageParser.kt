package me.rerere.rikkahub.data.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把 Happy 中继解密后的原始记录解析为结构化 [WorkMessagePart] 列表。
 *
 * 覆盖 happy-cli 的四种 agent 信封（acp / codex / output / event）、
 * 用户文本消息和旧版 role=session 事件记录；未识别的类型落为 [WorkMessagePart.Raw]，
 * 不再像旧实现那样压平成单条文本。
 */
object WorkMessageParser {

    data class Parsed(
        val role: WorkRole,
        val parts: List<WorkMessagePart>,
        /** 用户消息 meta 中的 permissionMode，用于恢复会话级执行模式 */
        val permissionMode: String? = null,
    )

    /** 返回 null 表示该记录不需要展示（如 token_count 统计） */
    fun parse(record: JsonObject): Parsed? {
        val role = record.string("role") ?: return null
        val content = record["content"] as? JsonObject ?: return null
        return when (role) {
            "user" -> parseUser(content, record["meta"] as? JsonObject)
            "agent" -> parseAgent(content)
            "session" -> parseLegacySession(content)
            else -> null
        }
    }

    private fun parseUser(content: JsonObject, meta: JsonObject?): Parsed? {
        val text = when (content.string("type")) {
            "text" -> content.string("text")
            else -> content.string("text") ?: content.string("message")
        } ?: return null
        return Parsed(
            role = WorkRole.USER,
            parts = listOf(WorkMessagePart.Text(text)),
            permissionMode = meta?.string("permissionMode"),
        )
    }

    private fun parseAgent(content: JsonObject): Parsed? {
        val data = content["data"] as? JsonObject
        val parts = when (content.string("type")) {
            "acp", "codex" -> data?.let { listOfNotNull(parseAgentEvent(it)) }
            "output" -> data?.let(::parseClaudeOutput)
            "event" -> data?.let { listOfNotNull(parseSessionEvent(it)) }
            else -> when {
                data != null -> listOfNotNull(parseAgentEvent(data))
                else -> content.string("text")?.let { listOf(WorkMessagePart.Text(it)) }
            }
        }.orEmpty()
        if (parts.isEmpty()) return null
        return Parsed(WorkRole.AGENT, parts)
    }

    /**
     * ACP 统一格式与 codex 信封共用的事件体。
     * codex 用 tool-call-result 命名工具结果，ACP 用 tool-result，其余字段一致。
     */
    private fun parseAgentEvent(data: JsonObject): WorkMessagePart? = when (val type = data.string("type")) {
        "message" -> data.string("message")?.let { WorkMessagePart.Text(it) }
        "reasoning" -> data.string("message")?.let { WorkMessagePart.Reasoning(it) }
        "thinking" -> data.string("text")?.let { WorkMessagePart.Reasoning(it) }
        "tool-call" -> WorkMessagePart.ToolCall(
            name = data.string("name") ?: "tool",
            input = data["input"].toCompactText(),
            callId = data.string("callId"),
        )
        "tool-result", "tool-call-result" -> WorkMessagePart.ToolResult(
            output = data["output"].toCompactText(),
            callId = data.string("callId"),
            isError = data.boolean("isError") ?: false,
        )
        "file-edit" -> WorkMessagePart.FileEdit(
            filePath = data.string("filePath") ?: "",
            description = data.string("description"),
            diff = data.string("diff"),
        )
        "terminal-output" -> data.string("data")?.let { WorkMessagePart.Terminal(it) }
        "task_started", "task_complete", "turn_aborted", "ready",
        "switch", "permission-mode-changed" -> WorkMessagePart.Event(
            kind = type,
            text = data.string("message") ?: data.string("mode"),
        )
        "permission-request" -> WorkMessagePart.Event(
            kind = "permission-request",
            text = data.string("description") ?: data.string("toolName"),
        )
        "token_count" -> null
        else -> fallbackRaw(type, data)
    }

    /** happy-cli sendSessionEvent 的 event 信封 */
    private fun parseSessionEvent(data: JsonObject): WorkMessagePart? = when (val type = data.string("type")) {
        "message" -> data.string("message")?.let { WorkMessagePart.Event("message", it) }
        "switch", "permission-mode-changed" -> WorkMessagePart.Event(
            kind = type,
            text = data.string("mode"),
        )
        "ready" -> WorkMessagePart.Event("ready")
        else -> fallbackRaw(type, data)
    }

    /**
     * Claude Code 原始 stream-json 行（content.type == "output"）。
     * assistant/user 行展开 message.content 块，system/result 行降为事件。
     */
    private fun parseClaudeOutput(data: JsonObject): List<WorkMessagePart> = when (data.string("type")) {
        "assistant", "user" -> {
            val blocks = (data["message"] as? JsonObject)?.get("content")
            when (blocks) {
                is JsonArray -> blocks.mapNotNull { block -> (block as? JsonObject)?.let(::parseClaudeBlock) }
                is JsonPrimitive -> blocks.contentOrNull?.let { listOf(WorkMessagePart.Text(it)) }.orEmpty()
                else -> emptyList()
            }
        }
        "system" -> listOf(WorkMessagePart.Event(kind = "system", text = data.string("subtype")))
        "result" -> listOf(
            WorkMessagePart.Event(kind = "result", text = data.string("result") ?: data.string("subtype"))
        )
        else -> listOfNotNull(fallbackRaw(data.string("type"), data))
    }

    private fun parseClaudeBlock(block: JsonObject): WorkMessagePart? = when (block.string("type")) {
        "text" -> block.string("text")?.takeIf(String::isNotBlank)?.let { WorkMessagePart.Text(it) }
        "thinking" -> block.string("thinking")?.takeIf(String::isNotBlank)?.let { WorkMessagePart.Reasoning(it) }
        "tool_use" -> WorkMessagePart.ToolCall(
            name = block.string("name") ?: "tool",
            input = block["input"].toCompactText(),
            callId = block.string("id"),
        )
        "tool_result" -> WorkMessagePart.ToolResult(
            output = block["content"].toCompactText(),
            callId = block.string("tool_use_id"),
            isError = block.boolean("is_error") ?: false,
        )
        else -> null
    }

    /** 旧版 role=session 记录：content.ev 携带事件 */
    private fun parseLegacySession(content: JsonObject): Parsed? {
        val event = content["ev"] as? JsonObject ?: return null
        val kind = event.string("t") ?: "event"
        val text = listOf("text", "title", "description", "name", "status")
            .firstNotNullOfOrNull { key -> event.string(key)?.takeIf(String::isNotBlank) }
        val role = if (content.string("role") == "user") WorkRole.USER else WorkRole.AGENT
        return Parsed(role, listOf(WorkMessagePart.Event(kind = kind, text = text)))
    }

    private fun fallbackRaw(type: String?, data: JsonObject): WorkMessagePart? {
        val text = listOf("message", "text", "description", "result", "status")
            .firstNotNullOfOrNull { key -> data.string(key)?.takeIf(String::isNotBlank) }
            ?: data["output"]?.toCompactText()?.takeIf(String::isNotBlank)
            ?: data["input"]?.toCompactText()?.takeIf(String::isNotBlank)
            ?: return null
        return WorkMessagePart.Raw(kind = type ?: "unknown", text = text)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonElement?.toCompactText(): String = when (this) {
        null -> ""
        is JsonPrimitive -> contentOrNull ?: toString()
        else -> toString()
    }
}
