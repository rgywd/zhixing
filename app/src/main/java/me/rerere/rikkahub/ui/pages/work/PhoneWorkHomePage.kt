package me.rerere.rikkahub.ui.pages.work

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.work.PhoneWorkSession
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

@Composable
fun PhoneWorkHomePage(vm: PhoneWorkHomeVM = koinViewModel()) {
    val navigator = LocalNavController.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Work") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = connection.configured && !refreshing) {
                        Icon(HugeIcons.Refresh01, "同步")
                    }
                    IconButton(onClick = { navigator.navigate(Screen.Setting) }) {
                        Icon(HugeIcons.Settings03, "Work 设置")
                    }
                },
            )
        },
        floatingActionButton = {
            if (connection.configured) {
                FloatingActionButton(onClick = { navigator.navigate(Screen.PhoneWorkSession("")) }) {
                    Icon(HugeIcons.Add01, "新建 Work 会话")
                }
            }
        },
    ) { padding ->
        when {
            !connection.configured -> EmptyWorkState(
                title = "还没有连接 Work Core",
                detail = "在原设置页的 Work 卡片中填写 HTTPS 地址和 Bearer Token。",
                modifier = Modifier.padding(padding),
                onClick = { navigator.navigate(Screen.Setting) },
            )

            sessions.isEmpty() -> EmptyWorkState(
                title = "开始一个 Codex 会话",
                detail = error ?: "选择仓库、模型和思考深度，然后像普通聊天一样发送消息。",
                modifier = Modifier.padding(padding),
                onClick = { navigator.navigate(Screen.PhoneWorkSession("")) },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (error != null) item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
                items(sessions, key = { it.id }) { session ->
                    WorkSessionRow(
                        session = session,
                        onClick = { navigator.navigate(Screen.PhoneWorkSession(session.id)) },
                        onComplete = { vm.complete(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkSessionRow(session: PhoneWorkSession, onClick: () -> Unit, onComplete: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.ComputerTerminal01, null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(session.repoName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${session.model} · ${session.reasoningEffort} · ${session.status.displayStatus()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (session.status == "COMPLETED") {
                Text(session.status.displayStatus(), style = MaterialTheme.typography.labelMedium)
            } else {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(HugeIcons.MoreVertical, "会话操作")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("结束会话") },
                            onClick = {
                                menuExpanded = false
                                onComplete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWorkState(title: String, detail: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp).clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HugeIcons.ComputerTerminal01, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun String.displayStatus(): String = when (this) {
    "QUEUED" -> "等待开发机"
    "RUNNING" -> "进行中"
    "WAITING_FOR_USER" -> "等你回答"
    "IDLE" -> "可继续"
    "COMPLETED" -> "已结束"
    "FAILED" -> "需要重试"
    else -> this
}
