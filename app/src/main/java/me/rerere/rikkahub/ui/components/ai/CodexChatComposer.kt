package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.data.workflow.codex.CodexModelOption
import me.rerere.rikkahub.data.workflow.codex.CodexPermissionProfile
import me.rerere.rikkahub.data.workflow.codex.CodexRuntimeSettingsState
import me.rerere.rikkahub.data.workflow.codex.CodexSkillOption
import me.rerere.rikkahub.data.workflow.codex.CodexPluginOption
import me.rerere.rikkahub.data.workflow.codex.CodexAppOption
import me.rerere.rikkahub.data.workflow.codex.CodexCatalogCapabilities
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.context.LocalSettings

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
    fastMode: Boolean,
    permissionProfiles: List<CodexPermissionProfile>,
    selectedPermission: String?,
    skills: List<CodexSkillOption>,
    plugins: List<CodexPluginOption>,
    apps: List<CodexAppOption>,
    capabilities: CodexCatalogCapabilities,
    selectedSkills: Set<String>,
    runtimeSettings: CodexRuntimeSettingsState,
    hazeState: HazeState,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onFastModeChange: (Boolean) -> Unit,
    onSelectPermission: (String) -> Unit,
    onToggleSkill: (CodexSkillOption) -> Unit,
    onAddAttachment: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    ChatInput(
        state = state,
        loading = loading,
        settings = settings,
        hazeState = hazeState,
        enableSearch = false,
        onToggleSearch = {},
        modifier = modifier,
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
            if (selectedModel?.serviceTiers?.any { it.id == "priority" } == true) {
                FilterChip(selected = fastMode, onClick = { onFastModeChange(!fastMode) }, label = { Text("Fast") })
            }
            OptionMenu(
                label = selectedPermission ?: "权限",
                options = permissionProfiles.filter { it.allowed }.map { it.id to permissionLabel(it.id) },
                onSelect = onSelectPermission,
            )
            MultiOptionMenu(
                label = if (selectedSkills.isEmpty()) "Skill" else "Skill ${selectedSkills.size}",
                options = skills.filter { it.enabled },
                selected = selectedSkills,
                onToggle = onToggleSkill,
            )
            ResourceMenu(
                label = "插件 ${plugins.count { it.installed && it.enabled }}",
                available = capabilities.plugins.available,
                error = capabilities.plugins.error,
                options = plugins.map { plugin ->
                    (plugin.interfaceInfo?.displayName ?: plugin.name) to
                        (plugin.interfaceInfo?.shortDescription ?: if (plugin.installed && plugin.enabled) "已启用" else "未启用")
                },
            )
            ResourceMenu(
                label = "App ${apps.count { it.isAccessible && it.isEnabled }}",
                available = capabilities.apps.available,
                error = capabilities.apps.error,
                options = apps.map { it.name to (it.description ?: if (it.isAccessible) "可用" else "未连接") },
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

@Composable
private fun MultiOptionMenu(
    label: String,
    options: List<CodexSkillOption>,
    selected: Set<String>,
    onToggle: (CodexSkillOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = selected.isNotEmpty(), onClick = { expanded = true }, label = { Text(label) })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { skill ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text((skill.interfaceInfo?.displayName ?: skill.name) + if (skill.name in selected) " ✓" else "")
                            Text(
                                skill.interfaceInfo?.shortDescription ?: skill.shortDescription ?: skill.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    },
                    onClick = { onToggle(skill) },
                )
            }
        }
    }
}

@Composable
private fun ResourceMenu(
    label: String,
    options: List<Pair<String, String>>,
    available: Boolean = true,
    error: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = false, onClick = { expanded = true }, label = { Text(label) })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!available) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("当前运行时不可用")
                            error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        }
                    },
                    onClick = { expanded = false },
                )
            } else if (options.isEmpty()) {
                DropdownMenuItem(text = { Text("当前运行时没有可用项") }, onClick = { expanded = false })
            } else options.forEach { (name, description) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(name)
                            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ComposerIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.size(34.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

private fun permissionLabel(id: String): String = when (id) {
    "read-only" -> "只读"
    "default" -> "默认"
    "full-access" -> "完全访问"
    else -> id
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
