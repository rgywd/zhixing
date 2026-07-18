package me.rerere.rikkahub.data.workflow

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart

/**
 * 把远程会话的结构化消息映射为主页聊天的 [UIMessagePart]，
 * 使会话页可以完整复用主页的消息渲染资产（markdown、代码高亮、
 * 可折叠思考卡、可展开工具卡等）。
 *
 * - ToolCall / ToolResult 按 callId 跨消息配对为 [UIMessagePart.Tool]
 * - 连续同角色消息合并为一个块，事件保持独立的小字注记
 */
sealed interface WorkChatItem {
    val key: String

    data class PartsBlock(
        override val key: String,
        val role: MessageRole,
        val parts: List<UIMessagePart>,
    ) : WorkChatItem

    data class Note(override val key: String, val text: String) : WorkChatItem

    data class Ask(
        override val key: String,
        val prompt: String,
        val questions: List<ClaudeQuestion>,
        val answered: Boolean,
    ) : WorkChatItem

    data class HtmlReport(override val key: String, val title: String, val html: String) : WorkChatItem
}

fun buildWorkChatItems(messages: List<WorkMessage>): List<WorkChatItem> {
    // 先按 callId 收集工具结果，配对到对应的调用
    val resultsByCallId = mutableMapOf<String, WorkMessagePart.ToolResult>()
    messages.asSequence().flatMap(WorkMessage::parts).forEach { part ->
        if (part is WorkMessagePart.ToolResult && part.callId != null) {
            resultsByCallId[part.callId] = part
        }
    }
    val pairedResults = mutableSetOf<String>()

    val items = mutableListOf<WorkChatItem>()
    var blockRole: MessageRole? = null
    var blockParts = mutableListOf<UIMessagePart>()
    var blockKey: String? = null

    fun flushBlock() {
        if (blockParts.isNotEmpty()) {
            items += WorkChatItem.PartsBlock(
                key = "block-$blockKey",
                role = blockRole ?: MessageRole.ASSISTANT,
                parts = blockParts.toList(),
            )
        }
        blockParts = mutableListOf()
        blockRole = null
        blockKey = null
    }

    fun appendPart(role: MessageRole, key: String, part: UIMessagePart) {
        if (blockRole != null && blockRole != role) flushBlock()
        if (blockKey == null) blockKey = key
        blockRole = role
        blockParts += part
    }

    messages.forEach { message ->
        val role = if (message.role == WorkRole.USER) MessageRole.USER else MessageRole.ASSISTANT
        message.parts.forEachIndexed { index, part ->
            val key = "${message.id}-$index"
            when (part) {
                is WorkMessagePart.Text -> appendPart(role, key, UIMessagePart.Text(part.text))
                is WorkMessagePart.Reasoning -> appendPart(role, key, UIMessagePart.Reasoning(reasoning = part.text))
                is WorkMessagePart.ToolCall -> {
                    val result = part.callId?.let(resultsByCallId::get)
                    result?.let { pairedResults += requireNotNull(part.callId) }
                    appendPart(
                        role, key,
                        UIMessagePart.Tool(
                            toolCallId = part.callId ?: key,
                            toolName = part.title ?: part.name,
                            input = part.input,
                            output = result?.let { listOf(UIMessagePart.Text(it.output)) }.orEmpty(),
                        )
                    )
                }
                is WorkMessagePart.ToolResult -> {
                    // 已配对的结果在 ToolCall 处渲染；孤立失败结果单独成卡
                    val paired = part.callId != null && part.callId in pairedResults
                    if (!paired && part.isError) {
                        appendPart(
                            role, key,
                            UIMessagePart.Tool(
                                toolCallId = part.callId ?: key,
                                toolName = "执行失败",
                                input = "",
                                output = listOf(UIMessagePart.Text(part.output)),
                            )
                        )
                    }
                }
                is WorkMessagePart.FileEdit -> appendPart(
                    role, key,
                    UIMessagePart.Tool(
                        toolCallId = key,
                        toolName = "edit_file",
                        input = listOfNotNull(part.filePath, part.description).joinToString("\n"),
                        output = part.diff?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(),
                    )
                )
                is WorkMessagePart.Terminal -> Unit // 仅完整日志展示
                is WorkMessagePart.Event -> {
                    workEventNote(part)?.let { note ->
                        flushBlock()
                        items += WorkChatItem.Note(key = key, text = note)
                    }
                }
                is WorkMessagePart.ClaudeAsk -> {
                    flushBlock()
                    items += WorkChatItem.Ask(
                        key = key,
                        prompt = part.prompt,
                        questions = part.questions,
                        answered = messages.any { later -> later.role == WorkRole.USER && later.seq > message.seq },
                    )
                }
                is WorkMessagePart.HtmlReport -> {
                    flushBlock()
                    items += WorkChatItem.HtmlReport(key = key, title = part.title, html = part.html)
                }
                is WorkMessagePart.Raw -> appendPart(
                    role, key,
                    UIMessagePart.Tool(
                        toolCallId = key,
                        toolName = part.kind,
                        input = "",
                        output = listOf(UIMessagePart.Text(part.text)),
                    )
                )
            }
        }
    }
    flushBlock()
    return items
}

internal fun workEventNote(part: WorkMessagePart.Event): String? = when (part.kind) {
    "task_complete" -> "任务完成"
    "turn_aborted" -> "本轮已中止"
    "turn-end" -> when (part.text) {
        "failed" -> "本轮执行失败"
        "cancelled" -> "本轮已取消"
        else -> null
    }
    "service" -> part.text?.takeIf(String::isNotBlank)
    "subagent-start" -> part.text?.let { "子任务：$it" } ?: "启动子任务"
    "result" -> part.text?.takeIf(String::isNotBlank)?.let { "结果：${it.take(200)}" } ?: "任务结束"
    "permission-mode-changed" -> part.text?.let { "执行模式已切换：$it" }
    "permission-request" -> part.text?.let { "请求权限：$it" } ?: "请求权限"
    "message" -> part.text
    else -> null
}
