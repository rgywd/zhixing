package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyMachine
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkflowSettingsPage(vm: WorkflowVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("工作连接") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "Happy 负责知行与开发机之间的端到端加密中继。连接信息和开发机状态只在这里管理。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when (vm.status) {
                WorkflowConnectionStatus.Connected -> {
                    item { ConnectedAccountCard(vm) }
                    item {
                        Text("开发机", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (vm.machines.isEmpty()) {
                        item { Text("尚未发现开发机，请确认电脑上的 Happy daemon 正在运行。") }
                    } else {
                        items(vm.machines, key = HappyMachine::id) { machine -> MachineItem(machine) }
                    }
                }
                else -> item { ConnectAccountCard(vm) }
            }
        }
    }
}

@Composable
private fun ConnectAccountCard(vm: WorkflowVM) {
    val isConnecting = vm.status == WorkflowConnectionStatus.Connecting
    val error = (vm.status as? WorkflowConnectionStatus.Error)?.message
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("连接 Happy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = vm.recoveryKey,
                onValueChange = vm::updateRecoveryKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("恢复密钥") },
                supportingText = { Text(error ?: "密钥只在本机使用，并由 Android Keystore 加密保存") },
                leadingIcon = { Icon(HugeIcons.LockKey, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = vm::toggleSecretVisibility, enabled = !isConnecting) {
                        Icon(
                            if (vm.isSecretVisible) HugeIcons.ViewOff else HugeIcons.View,
                            contentDescription = if (vm.isSecretVisible) "隐藏密钥" else "显示密钥",
                        )
                    }
                },
                visualTransformation = if (vm.isSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                isError = error != null,
                enabled = !isConnecting,
            )
            Button(
                onClick = vm::connect,
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.recoveryKey.isNotBlank() && !isConnecting,
            ) {
                if (isConnecting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("连接")
            }
        }
    }
}

@Composable
private fun ConnectedAccountCard(vm: WorkflowVM) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(HugeIcons.CheckmarkCircle02, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text("Happy 已连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "已同步 ${vm.projects.size} 个项目、${vm.sessions.size} 个历史对话",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            vm.syncError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth()) {
                Text("断开并清除本机凭据")
            }
        }
    }
}

@Composable
private fun MachineItem(machine: HappyMachine) {
    Card {
        ListItem(
            headlineContent = { Text(machine.displayName ?: machine.host) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        machine.platform,
                        machine.supportsCodex?.let { if (it) "Codex 可用" else "未发现 Codex" },
                    ).joinToString(" · ")
                )
            },
            leadingContent = { Icon(HugeIcons.ComputerTerminal01, contentDescription = null) },
            trailingContent = {
                Text(
                    if (machine.active) "在线" else "离线",
                    color = if (machine.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}
