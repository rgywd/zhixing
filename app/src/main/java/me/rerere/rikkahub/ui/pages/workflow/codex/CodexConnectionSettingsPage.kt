package me.rerere.rikkahub.ui.pages.workflow.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

@Composable
fun CodexConnectionSettingsPage(vm: CodexWorkflowVM = koinViewModel()) {
    val navController = LocalNavController.current
    Scaffold(
        topBar = { TopAppBar(title = { Text("开发环境") }, navigationIcon = { BackButton() }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Codex 项目与任务通过你的加密中继同步。中继只能看到密文与连接元数据。")
            OutlinedTextField(
                value = vm.relayUrl,
                onValueChange = vm::updateRelayUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("中继地址") },
                enabled = !vm.isConnected,
                singleLine = true,
            )
            if (!vm.isConnected) {
                OutlinedTextField(
                    value = vm.recoveryKey,
                    onValueChange = vm::updateRecoveryKey,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("恢复密钥") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Button(
                    onClick = vm::connect,
                    enabled = vm.recoveryKey.isNotBlank() && !vm.isRefreshing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (vm.isRefreshing) CircularProgressIndicator(Modifier.padding(4.dp)) else Text("连接")
                }
            } else {
                Text("已连接。项目目录会保存在本机，断网后仍可浏览。")
                OutlinedButton(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth()) { Text("断开连接") }
            }
            vm.statusMessage?.let { Text(it) }
            Text("0.2.0 不会删除旧版 Happy 凭据、缓存或服务器数据。需要核对历史时可进入只读页面。")
            OutlinedButton(
                onClick = { navController.navigate(Screen.LegacyWorkflow) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("查看 0.1.13 旧版历史（只读）") }
        }
    }
}
