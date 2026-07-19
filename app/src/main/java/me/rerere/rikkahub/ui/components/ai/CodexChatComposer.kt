package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import me.rerere.rikkahub.data.workflow.codex.CodexModelOption
import me.rerere.rikkahub.data.workflow.codex.CodexRuntimeSettingsState
import me.rerere.rikkahub.data.workflow.codex.CodexSkillOption
import me.rerere.rikkahub.data.workflow.codex.CodexPluginOption
import me.rerere.rikkahub.data.workflow.codex.CodexAppOption
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.ai.completion.CodexCommandCompletionProvider

/**
 * Codex adapter for the existing Zhixing composer primitives. It deliberately
 * shares [ChatInputState], [TextInputRow], and [MediaFileInputRow] with normal
 * chat; only the runtime-specific option catalog differs.
 */
@Composable
fun CodexChatComposer(
    state: ChatInputState,
    loading: Boolean,
    canSend: Boolean,
    models: List<CodexModelOption>,
    selectedModel: CodexModelOption?,
    selectedEffort: String?,
    skills: List<CodexSkillOption>,
    plugins: List<CodexPluginOption>,
    apps: List<CodexAppOption>,
    runtimeSettings: CodexRuntimeSettingsState,
    hazeState: HazeState,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val completionProviders = remember(skills, plugins, apps) {
        listOf(CodexCommandCompletionProvider(skills, plugins, apps))
    }
    ChatInput(
        state = state,
        loading = loading,
        settings = settings,
        hazeState = hazeState,
        enableSearch = false,
        onToggleSearch = {},
        modifier = modifier,
        completionProviders = completionProviders,
        onUpdateChatModel = {},
        onUpdateAssistant = {},
        onUpdateSearchService = {},
        onMoreClick = onAddAttachment,
        onCancelClick = onStop,
        onSendClick = onSend,
        onLongSendClick = onSend,
        canSend = canSend,
        allowSendWhileLoading = true,
        statusContent = {
            runtimeLabel(runtimeSettings)?.let { label ->
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        controlContent = {
            OptionMenu(
                label = selectedModel?.displayName ?: "模型",
                options = models.map { it.id to it.displayName },
                onSelect = onSelectModel,
            )
            OptionMenu(
                label = selectedEffort ?: "思考",
                options = selectedModel?.supportedReasoningEfforts.orEmpty()
                    .map { it.reasoningEffort to it.reasoningEffort },
                onSelect = onSelectEffort,
            )
        },
    )
}

@Composable
private fun OptionMenu(label: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(id) })
            }
        }
    }
}

private fun runtimeLabel(settings: CodexRuntimeSettingsState): String? {
    val values = listOfNotNull(
        settings.model,
        settings.effort,
        "Fast".takeIf { settings.serviceTier == "priority" },
        settings.permissions,
        settings.contextPercent?.let { percent ->
            val used = settings.usedTokens ?: 0
            val window = settings.contextWindow ?: 0
            "上下文 $percent% · ${used / 1_000}k/${window / 1_000}k"
        },
    )
    return values.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
