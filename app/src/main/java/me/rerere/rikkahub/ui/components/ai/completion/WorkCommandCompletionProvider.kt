package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange

class WorkCommandCompletionProvider(
    private val runtime: String,
) : ChatCompletionProvider {
    override val id: String = "work_commands"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (context.hasSelection || context.cursor != context.text.length) return null
        val input = context.text.trim()
        if (!input.startsWith('/') || input.any(Char::isWhitespace)) return null
        val commands = when (runtime) {
            "claude-code" -> listOf(
                WorkCommand("/compact", "压缩当前 Claude Code 会话上下文"),
                WorkCommand("/context", "查看 Claude Code 当前上下文构成"),
            )
            "codex" -> listOf(
                WorkCommand("/compact", "压缩当前 Codex 会话上下文"),
            )
            else -> emptyList()
        }
        val matches = commands.filter { it.name.startsWith(input, ignoreCase = true) }
        if (matches.isEmpty()) return null
        val start = context.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        return ChatCompletionList(
            providerId = id,
            replacementRange = TextRange(start, context.cursor),
            items = matches.map { command ->
                ChatCompletionItem(
                    label = command.name,
                    insertText = command.name,
                    detail = command.detail,
                )
            },
        )
    }

    private data class WorkCommand(val name: String, val detail: String)
}
