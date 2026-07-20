package me.rerere.rikkahub.ui.pages.workflow.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.rikkahub.data.work.WorkRepositoryConfig
import me.rerere.rikkahub.data.workflow.codex.CodexPermissionProfile
import me.rerere.rikkahub.ui.components.ai.NativeChatAttachmentSheet
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState

@Composable
fun CodexWorkOptionsSheet(
    state: ChatInputState,
    fastMode: Boolean,
    fastSupported: Boolean,
    permissionProfiles: List<CodexPermissionProfile>,
    selectedPermission: String?,
    repositories: List<WorkRepositoryConfig> = emptyList(),
    selectedRepositoryId: String? = null,
    onFastModeChange: (Boolean) -> Unit,
    onPermissionChange: (String) -> Unit,
    onRepositoryChange: (String) -> Unit = {},
    onCompact: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    NativeChatAttachmentSheet(
        state = state,
        settings = LocalSettings.current,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (repositories.isNotEmpty()) {
                Text("当前仓库", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repositories.forEach { repository ->
                        Surface(
                            onClick = { onRepositoryChange(repository.id) },
                            color = if (repository.id == selectedRepositoryId) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(repository.displayName, Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                        }
                    }
                }
            }
            ListItem(
                leadingContent = { Icon(HugeIcons.Folder01, contentDescription = null) },
                headlineContent = { Text("工作区") },
                supportingContent = {
                    Text(repositories.firstOrNull { it.id == selectedRepositoryId }?.path ?: "未选择仓库")
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.clickable(enabled = false) {},
            )
            ListItem(
                leadingContent = { Icon(HugeIcons.Package01, contentDescription = null) },
                headlineContent = { Text("压缩历史") },
                supportingContent = { Text("让 Codex 压缩当前 Thread 的上下文") },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.clickable {
                    onCompact()
                    onDismiss()
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Fast 模式") },
                supportingContent = { Text("模型支持时使用 Codex priority 服务层") },
                trailingContent = {
                    Switch(
                        checked = fastMode,
                        onCheckedChange = onFastModeChange,
                        enabled = fastSupported,
                    )
                },
            )
            Text("访问权限", style = MaterialTheme.typography.titleSmall)
            permissionProfiles.filter { it.allowed }.forEach { profile ->
                Surface(
                    onClick = { onPermissionChange(profile.id) },
                    color = if (selectedPermission == profile.id) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(permissionLabel(profile.id))
                        profile.description?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(
                "在输入框键入 / 可选择 Skill、插件或 App。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun permissionLabel(id: String): String = when (id) {
    "read-only" -> "只读"
    "default" -> "默认"
    "full-access" -> "完全访问"
    else -> id
}
