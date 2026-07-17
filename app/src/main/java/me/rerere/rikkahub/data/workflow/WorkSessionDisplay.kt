package me.rerere.rikkahub.data.workflow

/**
 * 会话统计摘要。聊天流展示已收敛到主页渲染资产，见 [buildWorkChatItems]。
 */
data class WorkSessionStats(
    val toolCalls: Int,
    val editedFiles: Int,
    val failures: Int,
)

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
