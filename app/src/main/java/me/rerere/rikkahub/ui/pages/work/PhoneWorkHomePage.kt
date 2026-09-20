package me.rerere.rikkahub.ui.pages.work

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Archive02
import me.rerere.hugeicons.stroke.MessageNotification01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PlayCircle
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.work.PhoneWorkSession
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import org.koin.androidx.compose.koinViewModel

private val ACTIVE_STATUSES = setOf("QUEUED", "RUNNING")

internal data class WorkSessionGroup(
    val key: String,
    val title: String,
    val sessions: List<PhoneWorkSession>,
)

internal fun groupWorkSessions(sessions: List<PhoneWorkSession>): List<WorkSessionGroup> {
    val waiting = sessions.filter { it.status == "WAITING_FOR_USER" }
    val active = sessions.filter { it.status in ACTIVE_STATUSES }
    val rest = sessions.filter { it.status != "WAITING_FOR_USER" && it.status !in ACTIVE_STATUSES }
    return buildList {
        if (waiting.isNotEmpty()) add(WorkSessionGroup("waiting", "等你回答", waiting))
        if (active.isNotEmpty()) add(WorkSessionGroup("active", "进行中", active))
        if (rest.isNotEmpty()) add(WorkSessionGroup("rest", "其余会话", rest))
    }
}

internal data class WorkHomeSummary(val waiting: Int, val active: Int)

internal fun summarizeWorkSessions(sessions: List<PhoneWorkSession>): WorkHomeSummary = WorkHomeSummary(
    waiting = sessions.count { it.status == "WAITING_FOR_USER" },
    active = sessions.count { it.status in ACTIVE_STATUSES },
)

@Composable
fun PhoneWorkHomePage(vm: PhoneWorkHomeVM = koinViewModel()) {
    val navigator = LocalNavController.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val showArchived by vm.showArchived.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showArchived) "已归档 Work" else "Work") },

                actions = {
                    IconButton(onClick = vm::toggleArchived) {
                        Icon(if (showArchived) HugeIcons.PlayCircle else HugeIcons.Archive02, if (showArchived) "返回进行中的会话" else "查看归档")
                    }
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
                detail = "从 Chat 一键导入配置，或手动连接 Work Core。",
                actionLabel = "导入或连接",
                modifier = Modifier.padding(padding),
                onClick = { navigator.navigate(Screen.Setting) },
            )

            sessions.isEmpty() -> EmptyWorkState(
                title = if (showArchived) "还没有归档会话" else "开始一个开发机 AI 会话",
                detail = error
                    ?: if (showArchived) {
                        "结束或暂停的会话可以归档后在这里恢复。"
                    } else {
                        "选择 Codex 或 Claude Code、仓库、模型和思考深度，然后像普通聊天一样发送消息。"
                    },
                actionLabel = if (showArchived) "返回进行中" else "新建会话",
                modifier = Modifier.padding(padding),
                onClick = { if (showArchived) vm.toggleArchived() else navigator.navigate(Screen.PhoneWorkSession("")) },
            )

            else -> {
                val groups = remember(sessions, showArchived) {
                    if (showArchived) emptyList() else groupWorkSessions(sessions)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!showArchived) {
                        item(key = "summary") {
                            WorkHomeSummaryCard(summary = summarizeWorkSessions(sessions))
                        }
                    }
                    if (error != null) {
                        item(key = "error") {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        error.orEmpty(),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = vm::refresh, enabled = connection.configured && !refreshing) {
                                        Text("重试")
                                    }
                                }
                            }
                        }
                    }
                    if (showArchived || groups.size <= 1) {
                        items(sessions, key = { it.id }) { session ->
                            WorkSessionCard(
                                session = session,
                                onClick = { navigator.navigate(Screen.PhoneWorkSession(session.id)) },
                                onComplete = { vm.complete(session.id) },
                                archived = showArchived,
                                onArchive = { vm.archive(session.id) },
                                onUnarchive = {
                                    vm.unarchive(session.id) {
                                        vm.toggleArchived()
                                        navigator.navigate(Screen.PhoneWorkSession(session.id))
                                    }
                                },
                            )
                        }
                    } else {
                        groups.forEach { group ->
                            item(key = "group:${group.key}") {
                                Text(
                                    "${group.title} · ${group.sessions.size}",
                                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, bottom = 2.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(group.sessions, key = { it.id }) { session ->
                                WorkSessionCard(
                                    session = session,
                                    onClick = { navigator.navigate(Screen.PhoneWorkSession(session.id)) },
                                    onComplete = { vm.complete(session.id) },
                                    archived = showArchived,
                                    onArchive = { vm.archive(session.id) },
                                    onUnarchive = {
                                        vm.unarchive(session.id) {
                                            vm.toggleArchived()
                                            navigator.navigate(Screen.PhoneWorkSession(session.id))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkHomeSummaryCard(summary: WorkHomeSummary) {
    val needsAttention = summary.waiting > 0
    val accentColor = if (needsAttention) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val icon: ImageVector = when {
        summary.waiting > 0 -> HugeIcons.MessageNotification01
        summary.active > 0 -> HugeIcons.PlayCircle
        else -> HugeIcons.Sparkles
    }
    val headline = when {
        summary.waiting > 0 -> "${summary.waiting} 个会话等你回答"
        summary.active > 0 -> "${summary.active} 个会话进行中"
        else -> "没有需要处理的任务"
    }
    val detail = when {
        summary.waiting > 0 && summary.active > 0 -> "另有 ${summary.active} 个正在运行"
        summary.waiting > 0 -> "回答后开发机会立刻继续"
        summary.active > 0 -> "有进展时会通知你"
        else -> "新建一个会话，让开发机开始工作"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(accentColor.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, modifier = Modifier.size(22.dp), tint = accentColor)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(headline, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WorkSessionCard(
    session: PhoneWorkSession,
    onClick: () -> Unit,
    onComplete: () -> Unit,
    archived: Boolean,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val waiting = session.status == "WAITING_FOR_USER"
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            if (waiting) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(3.dp)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
            }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 14.dp, end = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title.ifBlank { session.repoName },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(HugeIcons.MoreVertical, "会话操作")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (archived) {
                            DropdownMenuItem(
                                text = { Text("恢复会话") },
                                onClick = { menuExpanded = false; onUnarchive() },
                            )
                        } else {
                            if (session.status !in setOf("QUEUED", "RUNNING", "WAITING_FOR_USER")) {
                                DropdownMenuItem(
                                    text = { Text("归档会话") },
                                    onClick = { menuExpanded = false; onArchive() },
                                )
                            }
                            if (session.status != "COMPLETED") {
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
            Text(
                "${session.runtime.workRuntimeName()} · ${session.repoName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("${session.model} · ${session.reasoningEffort}")
                    if (session.runtime == "codex") append(if (session.fastMode) " · 快速" else " · 标准")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkStatusChip(
                    status = session.status,
                    containerColor = if (waiting) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        workStatusContainerColor(session.status)
                    },
                    contentColor = if (waiting) {
                        MaterialTheme.colorScheme.onTertiary
                    } else {
                        workStatusOnContainerColor(session.status)
                    },
                    dotColor = if (waiting) {
                        MaterialTheme.colorScheme.onTertiary
                    } else {
                        workStatusColor(session.status)
                    },
                )
                relativeWorkTimeLabel(session.updatedAt)?.let { updated ->
                    Text(
                        updated,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun EmptyWorkState(title: String, detail: String, actionLabel: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    HugeIcons.Sparkles,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) {
                Text(actionLabel)
            }
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

private fun String.workRuntimeName(): String = when (this) {
    "claude-code" -> "Claude Code"
    else -> "Codex"
}
