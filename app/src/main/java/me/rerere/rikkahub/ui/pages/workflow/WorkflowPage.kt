package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.Screen
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.WorkflowCircle06
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyMachine
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySession
import me.rerere.rikkahub.ui.pages.workflow.happy.pendingApprovals
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkflowPage(vm: WorkflowVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = { BackButton() },
                title = { Text("工作流") },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WorkflowIntroCard()

            when (val status = vm.status) {
                WorkflowConnectionStatus.Connected -> ConnectedContent(
                    machines = vm.machines,
                    sessions = vm.sessions,
                    isRefreshing = vm.isRefreshing,
                    syncError = vm.syncError,
                    onRefresh = vm::refresh,
                    onDisconnect = vm::disconnect,
                )
                else -> ConnectCard(
                    recoveryKey = vm.recoveryKey,
                    isSecretVisible = vm.isSecretVisible,
                    isConnecting = status == WorkflowConnectionStatus.Connecting,
                    errorMessage = (status as? WorkflowConnectionStatus.Error)?.message,
                    onRecoveryKeyChange = vm::updateRecoveryKey,
                    onToggleSecretVisibility = vm::toggleSecretVisibility,
                    onConnect = vm::connect,
                )
            }
        }
    }
}

@Composable
private fun WorkflowIntroCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = HugeIcons.WorkflowCircle06,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "从知行远程掌控开发会话",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "开发机继续运行 Happy CLI 与 Codex。知行只通过 Happy 的加密中继同步会话，手机不会直接连接或暴露开发机端口。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ConnectCard(
    recoveryKey: String,
    isSecretVisible: Boolean,
    isConnecting: Boolean,
    errorMessage: String?,
    onRecoveryKeyChange: (String) -> Unit,
    onToggleSecretVisibility: () -> Unit,
    onConnect: () -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "连接 Happy 账户",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "粘贴 Happy 账户的恢复密钥。密钥仅在本机完成签名并由 Android Keystore 加密保存。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = recoveryKey,
                onValueChange = onRecoveryKeyChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting,
                label = { Text("恢复密钥") },
                placeholder = { Text("XXXX-XXXX-…") },
                singleLine = true,
                isError = errorMessage != null,
                supportingText = errorMessage?.let { message -> { Text(message) } },
                leadingIcon = {
                    Icon(HugeIcons.LockKey, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(
                        onClick = onToggleSecretVisibility,
                        enabled = !isConnecting,
                    ) {
                        Icon(
                            imageVector = if (isSecretVisible) HugeIcons.ViewOff else HugeIcons.View,
                            contentDescription = if (isSecretVisible) "隐藏密钥" else "显示密钥",
                        )
                    }
                },
                visualTransformation = if (isSecretVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConnect() }),
                shape = RoundedCornerShape(16.dp),
            )
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = recoveryKey.isNotBlank() && !isConnecting,
                shape = RoundedCornerShape(16.dp),
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp).height(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("正在连接")
                } else {
                    Text("安全连接")
                }
            }
        }
    }
}

@Composable
private fun ConnectedContent(
    machines: List<HappyMachine>,
    sessions: List<HappySession>,
    isRefreshing: Boolean,
    syncError: String?,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    ConnectedCard(
        machineCount = machines.size,
        sessionCount = sessions.size,
        isRefreshing = isRefreshing,
        syncError = syncError,
        onRefresh = onRefresh,
        onDisconnect = onDisconnect,
    )
    MachineSection(machines)
    SessionSection(sessions)
}

@Composable
private fun ConnectedCard(
    machineCount: Int,
    sessionCount: Int,
    isRefreshing: Boolean,
    syncError: String?,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.CheckmarkCircle02,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Happy 账户已连接",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "身份凭据已加密保存在此设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(20.dp).height(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(HugeIcons.Refresh01, contentDescription = "刷新")
                    }
                }
            }
            Text(
                text = "已同步 $machineCount 台机器、$sessionCount 个最近活跃会话。本页不会再打开 Happy 网页。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            syncError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("断开并清除本机凭据")
            }
        }
    }
}

@Composable
private fun MachineSection(machines: List<HappyMachine>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "开发机",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (machines.isEmpty()) {
            EmptySnapshotCard("尚未发现 Happy CLI 机器，请确认开发机上的 Happy 仍在运行。")
        } else {
            machines.forEach { machine ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = machine.displayName ?: machine.host,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            val detail = listOfNotNull(
                                machine.host.takeIf { it != machine.displayName },
                                machine.platform,
                                machine.supportsCodex?.let { if (it) "Codex 可用" else "未发现 Codex" },
                            ).joinToString(" · ")
                            if (detail.isNotBlank()) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = if (machine.active) "在线" else "离线",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (machine.active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSection(sessions: List<HappySession>) {
    val navController = LocalNavController.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "最近活跃会话",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (sessions.isEmpty()) {
            EmptySnapshotCard("当前没有最近 15 分钟内活跃的 Codex 会话。")
        } else {
            sessions.forEach { session ->
                Card(
                    onClick = { navController.navigate(Screen.WorkflowSession(session.id)) },
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(HugeIcons.Code, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.name
                                    ?: session.path?.substringAfterLast('/')?.substringAfterLast('\\')
                                    ?: "会话 ${session.id.take(8)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            session.path?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        Text(
                            text = when {
                                session.pendingApprovals > 0 -> "等待审批"
                                session.active -> "活跃"
                                else -> "离线"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (session.pendingApprovals > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySnapshotCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
