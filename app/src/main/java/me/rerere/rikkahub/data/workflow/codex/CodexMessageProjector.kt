package me.rerere.rikkahub.data.workflow.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Instant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

/**
 * The single presentation boundary between Codex app-server Items and Zhixing's
 * existing chat renderer. History snapshots and runtime events both update
 * [CodexItem], therefore they must both pass through this projector.
 */
data class CodexMessageBlock(
    val stableId: String,
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val loading: Boolean,
)

object CodexMessageProjector {
    fun project(detail: CodexThreadDetail): List<CodexMessageBlock> =
        detail.turns.flatMap { projectTurn(it, detail.approvals, detail.attachments) }

    fun projectTurn(
        turn: CodexTurn,
        approvals: List<CodexApproval> = emptyList(),
        attachments: Map<String, CodexAttachment> = emptyMap(),
    ): List<CodexMessageBlock> {
        val approvalsByItemId = approvals.mapNotNull { approval ->
            approval.itemId?.let { it to approval }
        }.toMap()
        val blocks = mutableListOf<CodexMessageBlock>()
        var currentRole: MessageRole? = null
        var currentParts = mutableListOf<UIMessagePart>()
        var currentIds = mutableListOf<String>()

        fun flush() {
            val role = currentRole ?: return
            if (currentParts.isNotEmpty()) {
                blocks += CodexMessageBlock(
                    stableId = "${turn.turnId}:${currentIds.joinToString(",")}",
                    role = role,
                    parts = currentParts.toList(),
                    loading = turn.status.isRunning() && blocks.isEmpty(),
                )
            }
            currentRole = null
            currentParts = mutableListOf()
            currentIds = mutableListOf()
        }

        turn.items.forEach { item ->
            val role = item.messageRole()
            val approval = approvalsByItemId[item.itemId]
            val parts = item.toParts(attachments, turn.startedAt, turn.completedAt).map { part ->
                if (part is UIMessagePart.Tool && approval != null) {
                    part.copy(approvalState = ToolApprovalState.Pending)
                } else {
                    part
                }
            }
            if (parts.isEmpty()) return@forEach
            if (currentRole != null && currentRole != role) flush()
            currentRole = role
            currentIds += item.itemId
            currentParts += parts
        }
        flush()
        return blocks.mapIndexed { index, block ->
            block.copy(loading = turn.status.isRunning() && index == blocks.lastIndex)
        }
    }

    private fun CodexItem.messageRole(): MessageRole = when (role) {
        "user" -> MessageRole.USER
        "tool", "agent" -> MessageRole.ASSISTANT
        "system" -> MessageRole.SYSTEM
        else -> MessageRole.ASSISTANT
    }

    private fun CodexItem.toParts(
        attachments: Map<String, CodexAttachment>,
        turnStartedAt: Long?,
        turnCompletedAt: Long?,
    ): List<UIMessagePart> = when (rawType) {
        "userMessage" -> userParts(attachments).ifEmpty { textPart() }
        "agentMessage", "plan" -> textPart()
        "reasoning" -> listOfNotNull(
            text?.takeIf(String::isNotBlank)?.let {
                val now = System.currentTimeMillis()
                val createdAtMillis = if (status.isRunning()) {
                    (turnStartedAt ?: now).coerceAtMost(now)
                } else {
                    turnStartedAt ?: (itemId.hashCode().toLong() and 0x7fff_ffffL)
                }
                val createdAt = Instant.fromEpochMilliseconds(createdAtMillis)
                if (status.isRunning()) UIMessagePart.Reasoning(reasoning = it, createdAt = createdAt, finishedAt = null)
                else UIMessagePart.Reasoning(
                    reasoning = it,
                    createdAt = createdAt,
                    finishedAt = Instant.fromEpochMilliseconds(turnCompletedAt ?: createdAt.toEpochMilliseconds()),
                )
            }
        )
        "commandExecution" -> listOf(toolPart("terminal", raw.string("command") ?: text.orEmpty()))
        "fileChange" -> listOf(toolPart("file_change", raw["changes"]?.toString().orEmpty()))
        "mcpToolCall" -> listOf(toolPart(toolDisplayName("mcp"), toolInput()))
        "dynamicToolCall" -> listOf(toolPart(toolDisplayName("tool"), toolInput()))
        "collabAgentToolCall" -> listOf(toolPart(toolDisplayName("agent"), toolInput()))
        "webSearch" -> listOf(toolPart("web_search", toolInput()))
        "imageGeneration" -> listOf(toolPart("image_generation", toolInput()))
        "contextCompaction" -> listOf(UIMessagePart.Reasoning(text ?: "上下文已压缩"))
        "enteredReviewMode", "exitedReviewMode" -> textPart()
        else -> textPart().ifEmpty {
            listOf(UIMessagePart.Tool(itemId, rawType.ifBlank { "codex" }, raw.toString()))
        }
    }

    private fun CodexItem.userParts(attachments: Map<String, CodexAttachment>): List<UIMessagePart> {
        val content = raw["content"] as? JsonArray ?: return emptyList()
        return content.mapNotNull { element ->
            val part = element as? JsonObject ?: return@mapNotNull null
            when (part.string("type")) {
                "text" -> part.string("text")?.let(UIMessagePart::Text)
                "image" -> part.string("url")?.let(UIMessagePart::Image)
                "localImage" -> part.string("path")?.let { path ->
                    attachments[path]?.let { attachment -> UIMessagePart.Image(attachment.localUri) }
                        ?: UIMessagePart.Text("[图片：${path.fileName()}（仅开发机可用）]")
                }
                "skill" -> part.string("name")?.let { UIMessagePart.Text("\$$it", metadata = part) }
                "mention" -> part.string("path")?.let { path ->
                    attachments[path]?.let { attachment ->
                        UIMessagePart.Document(attachment.localUri, attachment.fileName, attachment.mime)
                    } ?: part.string("name")?.let { UIMessagePart.Text("@$it", metadata = part) }
                }
                else -> null
            }
        }
    }

    private fun CodexItem.textPart(): List<UIMessagePart> =
        text?.takeIf(String::isNotBlank)?.let { listOf(UIMessagePart.Text(it)) }.orEmpty()

    private fun CodexItem.toolPart(name: String, input: String): UIMessagePart.Tool {
        val output = listOfNotNull(
            raw.string("aggregatedOutput")
                ?: raw.string("output")
                ?: raw.string("result")
        ).filter(String::isNotBlank).map(UIMessagePart::Text)
        return UIMessagePart.Tool(
            toolCallId = itemId,
            toolName = name,
            input = input.ifBlank { "{}" },
            output = output,
        )
    }

    private fun CodexItem.toolDisplayName(fallback: String): String {
        val namespace = raw.string("namespace")?.takeIf(String::isNotBlank)?.plus(".").orEmpty()
        return namespace + (raw.string("tool") ?: raw.string("name") ?: fallback)
    }

    private fun CodexItem.toolInput(): String =
        raw["arguments"]?.toString()
            ?: raw["input"]?.toString()
            ?: text
            ?: "{}"

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun String.fileName(): String = substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }

    private fun String?.isRunning(): Boolean = this == "inProgress" || this == "running"
}
