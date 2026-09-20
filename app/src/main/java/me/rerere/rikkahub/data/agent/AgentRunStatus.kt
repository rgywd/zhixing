package me.rerere.rikkahub.data.agent

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal fun agentRunStatus(messages: List<UIMessage>): String {
    val parts = messages.flatMap { it.parts }
    val tools = parts.filterIsInstance<UIMessagePart.Tool>()
    return when {
        tools.any { it.isPending && !it.isExecuted } -> "WAITING_FOR_INPUT"
        tools.any { !it.isExecuted } -> "FAILED"
        // Tool calls and their final answer can share one UIMessage; inspect the final part.
        parts.isEmpty() || parts.last() is UIMessagePart.Tool -> "FAILED"
        else -> "COMPLETED"
    }
}
