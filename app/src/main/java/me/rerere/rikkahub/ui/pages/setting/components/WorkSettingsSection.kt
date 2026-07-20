package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.work.WorkConnectionCredentials
import me.rerere.rikkahub.data.work.WorkConnectionStore
import me.rerere.rikkahub.data.work.AppServerJsonRpcClient
import me.rerere.rikkahub.data.work.AppServerConnectionPhase
import me.rerere.rikkahub.data.work.WorkRepositoryConfig
import me.rerere.rikkahub.data.work.WorkUiStore
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun WorkSettingsSection(
    modifier: Modifier = Modifier,
    connectionStore: WorkConnectionStore = koinInject(),
    workUiStore: WorkUiStore = koinInject(),
    appServerClient: AppServerJsonRpcClient = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val uiState by workUiStore.state.collectAsStateWithLifecycle()
    val connectionState by appServerClient.state.collectAsStateWithLifecycle()
    var connections by remember { mutableStateOf(connectionStore.load()) }
    val activeConnection = connections.connections.firstOrNull { it.connectionId == connections.activeConnectionId }
    var editConnection by remember { mutableStateOf(false) }
    var editRepository by remember { mutableStateOf<WorkRepositoryConfig?>(null) }
    var addRepository by remember { mutableStateOf(false) }

    CardGroup(
        modifier = modifier,
        title = { Text("Work") },
    ) {
        item(
            onClick = { editConnection = true },
            leadingContent = { Icon(HugeIcons.Link01, null) },
            headlineContent = { Text("开发机连接") },
            supportingContent = {
                Text(activeConnection?.let { "${it.displayName} · 已保存加密凭据" } ?: "未配置 App Server")
            },
        )
        item(
            leadingContent = { Icon(HugeIcons.ComputerTerminal01, null) },
            headlineContent = { Text("运行状态") },
            supportingContent = {
                Text(
                    when {
                        activeConnection == null -> "连接后可检查 Codex App Server 与 Tailscale"
                        connectionState.phase == AppServerConnectionPhase.READY -> "Codex App Server 已连接"
                        connectionState.phase == AppServerConnectionPhase.FAILED -> connectionState.error ?: "连接失败"
                        else -> connectionState.phase.name.lowercase()
                    }
                )
            },
        )
        uiState.repositories.forEach { repository ->
            item(
                onClick = { editRepository = repository },
                leadingContent = { Icon(HugeIcons.Folder01, null) },
                headlineContent = { Text(repository.displayName) },
                supportingContent = { Text(repository.path, maxLines = 2) },
            )
        }
        item(
            onClick = { addRepository = true },
            leadingContent = { Icon(HugeIcons.FolderAdd, null) },
            headlineContent = { Text("添加仓库") },
            supportingContent = { Text("只展示你明确添加的仓库，不自动导入历史 CWD") },
        )
        item(
            onClick = { navController.navigate(Screen.LegacyWorkflow) },
            leadingContent = { Icon(HugeIcons.TransactionHistory, null) },
            headlineContent = { Text("旧版 Work 历史（只读）") },
            supportingContent = { Text("保留 0.2.2 数据用于核对与回滚，不进入新版 Work 首页") },
        )
    }

    if (editConnection) {
        WorkConnectionDialog(
            existing = activeConnection,
            onDismiss = { editConnection = false },
            onSave = { connection ->
                connectionStore.save(connection)
                connections = connectionStore.load()
                editConnection = false
            },
        )
    }
    if (addRepository) {
        WorkRepositoryDialog(
            existing = null,
            onDismiss = { addRepository = false },
            onSave = { repository ->
                scope.launch { workUiStore.upsertRepository(repository) }
                addRepository = false
            },
        )
    }
    editRepository?.let { repository ->
        WorkRepositoryDialog(
            existing = repository,
            onDismiss = { editRepository = null },
            onSave = { updated ->
                scope.launch { workUiStore.upsertRepository(updated) }
                editRepository = null
            },
            onDelete = {
                scope.launch { workUiStore.removeRepository(repository.id) }
                editRepository = null
            },
        )
    }
}

@Composable
private fun WorkConnectionDialog(
    existing: WorkConnectionCredentials?,
    onDismiss: () -> Unit,
    onSave: (WorkConnectionCredentials) -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.displayName ?: "我的开发机") }
    var url by remember(existing) { mutableStateOf(existing?.appServerUrl.orEmpty()) }
    var token by remember(existing) { mutableStateOf("") }
    var supervisorUrl by remember(existing) { mutableStateOf(existing?.supervisorUrl.orEmpty()) }
    var supervisorToken by remember(existing) { mutableStateOf("") }
    val effectiveToken = token.ifBlank { existing?.appServerToken.orEmpty() }
    val effectiveSupervisorToken = if (supervisorUrl.isBlank()) ""
        else supervisorToken.ifBlank { existing?.supervisorToken.orEmpty() }
    val supervisorValid = if (supervisorUrl.isBlank() && effectiveSupervisorToken.isBlank()) {
        true
    } else {
        isValidSupervisorUrl(supervisorUrl) && effectiveSupervisorToken.isNotBlank()
    }
    val valid = name.isNotBlank() && isValidWorkUrl(url) && effectiveToken.isNotBlank() && supervisorValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开发机连接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("App Server WSS 地址") }, singleLine = true)
                OutlinedTextField(
                    token,
                    { token = it },
                    label = { Text(if (existing == null) "Bearer Token" else "Bearer Token（留空保持不变）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(supervisorUrl, { supervisorUrl = it }, label = { Text("Supervisor 地址（可选）") }, singleLine = true)
                OutlinedTextField(
                    supervisorToken,
                    { supervisorToken = it },
                    label = { Text(if (existing == null) "Supervisor Token（可选）" else "Supervisor Token（留空保持不变）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    "仅允许 wss://；本机调试可使用 ws://127.0.0.1。密钥由 Android Keystore 加密且不会回显。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        WorkConnectionCredentials(
                            connectionId = existing?.connectionId ?: Uuid.random().toString(),
                            displayName = name.trim(),
                            appServerUrl = url.trim(),
                            appServerToken = effectiveToken,
                            supervisorUrl = supervisorUrl.trim().takeIf(String::isNotEmpty),
                            supervisorToken = effectiveSupervisorToken.takeIf { supervisorUrl.isNotBlank() && it.isNotEmpty() },
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun WorkRepositoryDialog(
    existing: WorkRepositoryConfig?,
    onDismiss: () -> Unit,
    onSave: (WorkRepositoryConfig) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember(existing) { mutableStateOf(existing?.displayName.orEmpty()) }
    var path by remember(existing) { mutableStateOf(existing?.path.orEmpty()) }
    var machineId by remember(existing) { mutableStateOf(existing?.machineId.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添加 Work 仓库" else "编辑 Work 仓库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true)
                OutlinedTextField(path, { path = it }, label = { Text("开发机绝对路径") }, singleLine = true)
                OutlinedTextField(machineId, { machineId = it }, label = { Text("开发机 ID（可选）") }, singleLine = true)
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Icon(HugeIcons.Delete01, null)
                        Text("移除仓库", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && path.isNotBlank(),
                onClick = {
                    onSave(
                        (existing ?: WorkRepositoryConfig(displayName = name, path = path)).copy(
                            displayName = name.trim(),
                            path = path.trim(),
                            machineId = machineId.trim().takeIf(String::isNotEmpty),
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun isValidWorkUrl(value: String): Boolean {
    val url = value.trim().lowercase()
    return url.startsWith("wss://") || url.startsWith("ws://127.0.0.1") || url.startsWith("ws://localhost")
}

private fun isValidSupervisorUrl(value: String): Boolean {
    val url = value.trim().lowercase()
    return url.startsWith("https://") || url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
}
