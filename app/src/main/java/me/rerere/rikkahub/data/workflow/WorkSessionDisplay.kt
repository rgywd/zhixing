package me.rerere.rikkahub.data.workflow

/**
 * 会话页的移动聊天视图模型：把结构化消息流折叠为可读的展示项。
 * 工具调用/文件修改等连续活动合并为一张活动卡，思考单独折叠，
 * 终端输出与成功的工具结果只进完整日志页，不打扰聊天主视图。
 */
sealed interface WorkDisplayItem {
    val key: String

    data class UserText(override val key: String, val text: String) : WorkDisplayItem
    data class AgentText(override val key: String, val text: String) : WorkDisplayItem
    data class Thinking(override val key: String, val text: String) : WorkDisplayItem
    data class Activity(override val key: String, val entries: List<WorkActivityEntry>) : WorkDisplayItem
    data class EventNote(override val key: String, val text: String) : WorkDisplayItem
}

data class WorkActivityEntry(
    val label: String,
    val detail: String? = null,
    val isError: Boolean = false,
)

data class WorkSessionStats(
    val toolCalls: Int,
    val editedFiles: Int,
    val failures: Int,
)

fun buildDisplayItems(messages: List<WorkMessage>): List<WorkDisplayItem> {
    val items = mutableListOf<WorkDisplayItem>()
    var activity = mutableListOf<WorkActivityEntry>()
    var activityKey: String? = null

    fun flushActivity() {
        if (activity.isNotEmpty()) {
            items += WorkDisplayItem.Activity(key = "act-${activityKey}", entries = activity.toList())
            activity = mutableListOf()
            activityKey = null
        }
    }

    messages.forEach { message ->
        message.parts.forEachIndexed { index, part ->
            val key = "${message.id}-$index"
            when (part) {
                is WorkMessagePart.Text -> {
                    flushActivity()
                    items += if (message.role == WorkRole.USER) {
                        WorkDisplayItem.UserText(key, part.text)
                    } else {
                        WorkDisplayItem.AgentText(key, part.text)
                    }
                }
                is WorkMessagePart.Reasoning -> {
                    flushActivity()
                    items += WorkDisplayItem.Thinking(key, part.text)
                }
                is WorkMessagePart.ToolCall -> {
                    if (activityKey == null) activityKey = key
                    activity += WorkActivityEntry(
                        label = part.title ?: part.name,
                        detail = part.input.takeIf(String::isNotBlank)?.take(200),
                    )
                }
                is WorkMessagePart.ToolResult -> {
                    // 成功结果只进完整日志；失败必须在聊天流里可见
                    if (part.isError) {
                        if (activityKey == null) activityKey = key
                        activity += WorkActivityEntry(
                            label = "执行失败",
                            detail = part.output.takeIf(String::isNotBlank)?.take(200),
                            isError = true,
                        )
                    }
                }
                is WorkMessagePart.FileEdit -> {
                    if (activityKey == null) activityKey = key
                    activity += WorkActivityEntry(
                        label = "修改 ${part.filePath.substringAfterLast('/').substringAfterLast('\\')}",
                        detail = part.description,
                    )
                }
                is WorkMessagePart.Terminal -> Unit // 仅完整日志展示
                is WorkMessagePart.Event -> {
                    val note = eventNote(part) ?: return@forEachIndexed
                    flushActivity()
                    items += WorkDisplayItem.EventNote(key, note)
                }
                is WorkMessagePart.Raw -> {
                    if (activityKey == null) activityKey = key
                    activity += WorkActivityEntry(label = part.kind, detail = part.text.take(200))
                }
            }
        }
    }
    flushActivity()
    return items
}

fun sessionStats(messages: List<WorkMessage>): WorkSessionStats {
    var toolCalls = 0
    var failures = 0
    val files = mutableSetOf<String>()
    messages.asSequence().flatMap(WorkMessage::parts).forEach { part ->
        when (part) {
            is WorkMessagePart.ToolCall -> toolCalls++
            is WorkMessagePart.ToolResult -> if (part.isError) failures++
            is WorkMessagePart.FileEdit -> files += part.filePath
            else -> Unit
        }
    }
    return WorkSessionStats(toolCalls = toolCalls, editedFiles = files.size, failures = failures)
}

private fun eventNote(part: WorkMessagePart.Event): String? = when (part.kind) {
    "task_complete" -> "任务完成"
    "turn_aborted" -> "本轮已中止"
    "turn-end" -> when (part.text) {
        "failed" -> "本轮执行失败"
        "cancelled" -> "本轮已取消"
        else -> null // completed 是常态，不打扰
    }
    "service" -> part.text?.takeIf(String::isNotBlank)
    "subagent-start" -> part.text?.let { "子任务：$it" } ?: "启动子任务"
    "result" -> part.text?.takeIf(String::isNotBlank)?.let { "结果：${it.take(200)}" } ?: "任务结束"
    "permission-mode-changed" -> part.text?.let { "执行模式已切换：$it" }
    "permission-request" -> part.text?.let { "请求权限：$it" } ?: "请求权限"
    "message" -> part.text
    // ready/switch/system/tool-call-end/file/turn-start 等生命周期事件不进聊天流
    else -> null
}
