package me.rerere.rikkahub.work

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.work.PhoneWorkCredentialStore
import me.rerere.rikkahub.data.work.PhoneWorkRepoPreferenceStore
import me.rerere.rikkahub.data.work.WorkConfigTransfer
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.permission.NotificationAccessCard
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.WorkConnectionDialog
import org.koin.compose.koinInject

@Composable
fun WorkSettingsPage() {
    val context = LocalContext.current
    val navigator = LocalNavController.current
    val credentials: PhoneWorkCredentialStore = koinInject()
    val settings: SettingsStore = koinInject()
    val repoPreferences: PhoneWorkRepoPreferenceStore = koinInject()
    val connection by credentials.connection.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text("Work 设置") }, navigationIcon = { BackButton() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("从 Chat 导入配置", style = MaterialTheme.typography.titleMedium)
                    Text("导入模型、Work 连接、语音和主题设置。已有的这些配置会被替换，Chat 内容不会改变。", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(enabled = !importing, onClick = {
                        importing = true
                        message = null
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    val peer = context.packageName.replace("dev.sundby.zhixing.work", "dev.sundby.zhixing")
                                    check(context.packageManager.checkSignatures(context.packageName, peer) == PackageManager.SIGNATURE_MATCH)
                                    val bundle = context.contentResolver.call("content://$peer.work-config".toUri(), "import-config-v1", null, null)
                                    val payload = Json.decodeFromString<WorkConfigTransfer>(requireNotNull(bundle?.getString("config")))
                                    require(payload.version == 1)
                                    if (payload.baseUrl.isNotBlank() && payload.token.isNotBlank()) credentials.save(payload.baseUrl, payload.token)
                                    settings.update { payload.applyTo(it) }
                                    repoPreferences.importPreferences(payload.repoPreferences)
                                    context.getSharedPreferences("rikkahub.preferences", android.content.Context.MODE_PRIVATE).edit {
                                        putString("colorMode", payload.colorMode)
                                        putBoolean("amoledDark", payload.amoledDark)
                                    }
                                    payload.baseUrl.isNotBlank() && payload.token.isNotBlank()
                                }
                            }
                            message = result.fold(
                                onSuccess = {
                                    when {
                                        it -> "配置已导入，返回首页即可同步 Work 会话。"
                                        credentials.connection.value.configured -> "模型、语音和主题已导入，保留现有 Work 连接。"
                                        else -> "模型、语音和主题已导入。Chat 尚未配置 Work，请手动连接 Core。"
                                    }
                                },
                                onFailure = { "导入失败。请安装同一渠道、同一签名的新版 Chat，然后重试。" },
                            )
                            importing = false
                        }
                    }) { Text(if (importing) "正在导入…" else "一键导入") }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            OutlinedButton(onClick = { connecting = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (connection.configured) "管理 Work 连接" else "手动连接 Work Core")
            }
            NotificationAccessCard()
            TextButton(onClick = { navigator.navigate(Screen.SettingSpeech) }) { Text("语音设置") }
            TextButton(onClick = { navigator.navigate(Screen.SettingPreferencesTheme) }) { Text("外观设置") }
        }
    }
    if (connecting) WorkConnectionDialog(
        currentUrl = connection.baseUrl, connected = connection.configured,
        onDismiss = { connecting = false },
        onSave = { url, token -> credentials.save(url, token); connecting = false },
        onDisconnect = { credentials.clear(); connecting = false },
    )
}
