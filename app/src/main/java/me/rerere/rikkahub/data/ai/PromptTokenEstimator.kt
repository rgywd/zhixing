package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.math.ceil

private const val MESSAGE_OVERHEAD_TOKENS = 8
private const val TOOL_OVERHEAD_TOKENS = 16
private const val IMAGE_TOKEN_RESERVE = 4_096
private const val MEDIA_TOKEN_RESERVE = 8_192
private const val ATTACHMENT_METADATA_TOKENS = 64

/**
 * Conservative local estimate used only to decide whether to compact before a paid model call.
 *
 * Zhixing supports arbitrary OpenAI-compatible model IDs and has no provider-neutral tokenizer.
 * UTF-8 bytes / 3 deliberately overestimates ordinary English while remaining close to one token
 * per CJK code point. The provider-reported usage remains the billing/statistics source of truth.
 */
internal fun estimateTextTokens(text: String): Int {
    if (text.isEmpty()) return 0
    return ceil(text.toByteArray(Charsets.UTF_8).size / 3.0).toInt().coerceAtLeast(1)
}

internal fun estimatePromptTokens(
    messages: List<UIMessage>,
    tools: List<Tool>,
): Int {
    val messageTokens = messages.sumOf { message ->
        MESSAGE_OVERHEAD_TOKENS.toLong() + message.parts.sumOf(::estimatePartTokens)
    }
    val toolTokens = tools.sumOf { tool ->
        TOOL_OVERHEAD_TOKENS.toLong() +
            estimateTextTokens(tool.name) +
            estimateTextTokens(tool.description) +
            estimateTextTokens(tool.parameters()?.toString().orEmpty())
    }
    return (messageTokens + toolTokens)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

private fun estimatePartTokens(part: UIMessagePart): Long = when (part) {
    is UIMessagePart.Text -> estimateTextTokens(part.text).toLong()
    is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning).toLong()
    is UIMessagePart.Image -> IMAGE_TOKEN_RESERVE.toLong()
    is UIMessagePart.Video,
    is UIMessagePart.Audio,
        -> MEDIA_TOKEN_RESERVE.toLong()
    is UIMessagePart.Document -> (
        ATTACHMENT_METADATA_TOKENS +
            estimateTextTokens(part.fileName) +
            estimateTextTokens(part.mime)
        ).toLong()
    is UIMessagePart.Tool -> (
        estimateTextTokens(part.toolName) +
            estimateTextTokens(part.input) +
            part.output.sumOf(::estimatePartTokens)
        ).toLong()
    is UIMessagePart.ToolCall -> (
        estimateTextTokens(part.toolName) +
            estimateTextTokens(part.arguments)
        ).toLong()
    is UIMessagePart.ToolResult -> (
        estimateTextTokens(part.toolName) +
            estimateTextTokens(part.arguments.toString()) +
            estimateTextTokens(part.content.toString())
        ).toLong()
    UIMessagePart.Search -> 0L
}
