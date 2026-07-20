package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Package
import me.rerere.rikkahub.data.workflow.codex.CodexAppOption
import me.rerere.rikkahub.data.workflow.codex.CodexPluginOption
import me.rerere.rikkahub.data.workflow.codex.CodexSkillOption
import me.rerere.rikkahub.data.work.AppServerBuiltInCommands

/** Work commands share the native chat input's completion popup instead of occupying a toolbar row. */
class CodexCommandCompletionProvider(
    private val skills: List<CodexSkillOption>,
    private val plugins: List<CodexPluginOption>,
    private val apps: List<CodexAppOption>,
) : ChatCompletionProvider {
    override val id: String = "codex_commands"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (context.hasSelection) return null
        val command = findCommand(context.text, context.cursor) ?: return null
        val query = command.query.lowercase()
        val items = buildList {
            AppServerBuiltInCommands.available.forEach { builtIn ->
                add(
                    Candidate(
                        id = builtIn.name,
                        label = "/${builtIn.name}",
                        detail = builtIn.description,
                        search = builtIn.name,
                        priority = 400,
                    )
                )
            }
            skills.filter { it.enabled }.forEach { skill ->
                val displayName = skill.interfaceInfo?.displayName ?: skill.name
                add(
                    Candidate(
                        id = skill.name,
                        label = "/${skill.name}",
                        detail = skill.interfaceInfo?.shortDescription ?: skill.shortDescription ?: skill.description,
                        search = "$displayName ${skill.name}",
                        priority = 300,
                    )
                )
            }
            plugins.filter { it.installed && it.enabled }.forEach { plugin ->
                add(
                    Candidate(
                        id = plugin.name,
                        label = "/${plugin.name}",
                        detail = plugin.interfaceInfo?.shortDescription ?: "Codex 插件",
                        search = "${plugin.interfaceInfo?.displayName.orEmpty()} ${plugin.name}",
                        priority = 200,
                    )
                )
            }
            apps.filter { it.isAccessible && it.isEnabled }.forEach { app ->
                add(
                    Candidate(
                        id = app.name,
                        label = "/${app.name}",
                        detail = app.description ?: "Codex App",
                        search = app.name,
                        priority = 100,
                    )
                )
            }
        }
            .asSequence()
            .filter { query.isBlank() || it.search.lowercase().contains(query) || it.id.lowercase().startsWith(query) }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Candidate> { it.priority }.thenBy { it.label })
            .take(MAX_ITEMS)
            .map { candidate ->
                ChatCompletionItem(
                    label = candidate.label,
                    insertText = "/${candidate.id} ",
                    detail = candidate.detail,
                    icon = HugeIcons.Package,
                    sortScore = candidate.priority,
                )
            }
            .toList()
        if (items.isEmpty()) return null
        return ChatCompletionList(id, command.range, items)
    }

    private fun findCommand(text: String, cursor: Int): Command? {
        if (cursor !in 0..text.length) return null
        val prefix = text.substring(0, cursor)
        val slash = prefix.lastIndexOf('/')
        if (slash < 0 || (slash > 0 && !prefix[slash - 1].isCommandBoundary())) return null
        val query = prefix.substring(slash + 1)
        if (query.any(Char::isWhitespace)) return null
        return Command(query, TextRange(slash, cursor))
    }

    private data class Command(val query: String, val range: TextRange)
    private data class Candidate(
        val id: String,
        val label: String,
        val detail: String,
        val search: String,
        val priority: Int,
    )

    private fun Char.isCommandBoundary(): Boolean =
        isWhitespace() || this in setOf(',', '，', '。', '；', ';', '!', '?', '！', '？')

    private companion object {
        const val MAX_ITEMS = 12
    }
}
