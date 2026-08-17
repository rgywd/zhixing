package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolExecutionException
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.HealthMetricChatSource

internal fun bindHealthMetricChatSource(
    sourceQuote: String,
    conversationId: String,
    messages: List<UIMessage>,
    recordedAtEpochMillis: Long = System.currentTimeMillis(),
): HealthMetricChatSource {
    val quote = sourceQuote.trim()
    if (quote.length !in MIN_SOURCE_QUOTE_LENGTH..MAX_SOURCE_QUOTE_LENGTH) {
        throw ToolExecutionException("HEALTH_SOURCE_INVALID")
    }
    val message = messages.asReversed()
        .filter { it.role == MessageRole.USER }
        .firstOrNull { candidate ->
            candidate.parts.filterIsInstance<UIMessagePart.Text>().any { part -> part.text.contains(quote) }
        }
        ?: throw ToolExecutionException("HEALTH_SOURCE_INVALID")
    return HealthMetricChatSource(
        conversationId = conversationId,
        messageId = message.id.toString(),
        recordedAtEpochMillis = recordedAtEpochMillis,
    )
}

private const val MIN_SOURCE_QUOTE_LENGTH = 2
private const val MAX_SOURCE_QUOTE_LENGTH = 500
