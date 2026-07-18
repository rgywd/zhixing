package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.workflow.RepoPreset
import me.rerere.rikkahub.data.workflow.WorkAgent
import me.rerere.rikkahub.data.workflow.WorkSession
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.toLocalDateTime
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkflowPage(
    readOnly: Boolean = false,
    vm: WorkflowVM = koinViewModel(),
) {
    val navController = LocalNavController.current
    var searchActive by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (searchActive) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            searchActive = false
                            vm.clearSearch()
                        }) {
                            Icon(HugeIcons.ArrowRight01, contentDescription = "退出搜索", modifier = Modifier.rotate(180f))
                        }
                    },
                    title = {
                        OutlinedTextField(
                            value = vm.searchQuery,
                            onValueChange = vm::updateSearchQuery,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索会话、仓库或内容") },
                            singleLine = true,
                        )
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(if (readOnly) "旧版历史" else "工作") },
                    navigationIcon = { BackButton() },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(HugeIcons.Search01, contentDescription = "搜索会话")
                        }
                        IconButton(onClick = vm::refresh, enabled = !vm.isRefreshing) {
                            if (vm.isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(HugeIcons.Refresh01, contentDescription = "刷新")
                            }
                        }
                        if (!readOnly) {
                            IconButton(onClick = { navController.navigate(Screen.WorkflowSettings) }) {
                                Icon(HugeIcons.Settings03, contentDescription = "工作连接设置")
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!readOnly && vm.status == WorkflowConnectionStatus.Connected) {
                FloatingActionButton(onClick = { navController.navigate(Screen.WorkNewTask()) }) {
                    Icon(HugeIcons.Add01, contentDescription = "新建任务")
                }
            }
        },
    ) { contentPadding ->
        when {
            searchActive -> SearchResults(
                results = vm.searchResults,
                query = vm.searchQuery,
                contentPadding = contentPadding,
                onOpen = { session ->
                    navController.navigate(
                        if (readOnly) Screen.LegacyWorkflowSession(session.id) else Screen.WorkflowSession(session.id)
                    )
                },
            )
            !readOnly && vm.status != WorkflowConnectionStatus.Connected -> WorkflowEmptyState(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                title = "连接开发环境后开始工作",
                description = "在设置中连接一次 Happy，之后这里会保留全部远程 Coding 会话。",
                action = "前往连接设置",
                onAction = { navController.navigate(Screen.WorkflowSettings) },
            )
            vm.isRefreshing && vm.sessions.isEmpty() && vm.presets.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> WorkHomeContent(vm = vm, contentPadding = contentPadding, readOnly = readOnly)
        }
    }
}

@Composable
private fun WorkHomeContent(vm: WorkflowVM, contentPadding: PaddingValues, readOnly: Boolean) {
    val navController = LocalNavController.current
    val waiting = vm.sessions.filter { it.approvals.isNotEmpty() }
    val running = vm.sessions.filter { it.active && it.approvals.isEmpty() }
    val recent = vm.sessions.filter { !it.active && it.approvals.isEmpty() }
        .let { if (readOnly) it else it.take(15) }
    fun sessionScreen(session: WorkSession): Screen =
        if (readOnly) Screen.LegacyWorkflowSession(session.id) else Screen.WorkflowSession(session.id)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        vm.syncError?.let { message -> item { InlineStatus(message, vm::refresh) } }
        if (readOnly) {
            item {
                Text(
                    "这里仅保留 0.1.13 Happy 历史用于查看和回滚；不能发送、审批、恢复或删除。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }

        if (waiting.isNotEmpty()) {
            item { SectionHeader("等待我处理 (${waiting.size})") }
            items(waiting, key = { "waiting-${it.id}" }) { session ->
                SessionListItem(session, highlight = true) {
                    navController.navigate(sessionScreen(session))
                }
            }
        }
        if (running.isNotEmpty()) {
            item { SectionHeader("进行中 (${running.size})") }
            items(running, key = { "running-${it.id}" }) { session ->
                SessionListItem(session) { navController.navigate(sessionScreen(session)) }
            }
        }
        if (!readOnly) {
            item { SectionHeader("仓库") }
            item {
                PresetRow(
                    presets = vm.presets,
                    onOpen = { preset ->
                        navController.navigate(Screen.WorkflowProject(preset.machineId, preset.path))
                    },
                    onNewTask = { preset -> navController.navigate(Screen.WorkNewTask(presetId = preset.id)) },
                    onCreate = { navController.navigate(Screen.WorkPresetEdit()) },
                )
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionHeader("最近") }
            items(recent, key = { "recent-${it.id}" }) { session ->
                SessionListItem(session) { navController.navigate(sessionScreen(session)) }
            }
        }
        if (vm.sessions.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (readOnly) "没有可查看的旧版 Happy 历史。" else
                            "还没有远程会话。先添加仓库预设，或直接新建任务。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!readOnly) {
                        Button(onClick = { navController.navigate(Screen.WorkNewTask()) }) { Text("新建任务") }
                    }
                }
            }
        }
    }
}

@Composable
fun WorkflowProjectPage(
    machineId: String,
    path: String,
    vm: WorkflowVM = koinViewModel(),
) {
    val navController = LocalNavController.current
    val project = vm.projects.firstOrNull { it.machineId == machineId && sameProjectPath(it.path, path) }
    val preset = vm.presets.firstOrNull { it.machineId == machineId && sameProjectPath(it.path, path) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        preset?.name ?: project?.name ?: projectName(path),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            navController.navigate(
                                Screen.WorkNewTask(
                                    presetId = preset?.id,
                                    machineId = machineId,
                                    path = project?.path ?: path,
                                )
                            )
                        },
                    ) {
                        Icon(HugeIcons.Add01, contentDescription = "在此仓库新建任务")
                    }
                },
            )
        },
    ) { contentPadding ->
        if (project == null && preset == null) {
            if (vm.isRefreshing) {
                Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                WorkflowEmptyState(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    title = "仓库暂时不可用",
                    description = vm.syncError ?: "会话历史可能已被删除，返回后刷新重试。",
                    action = "刷新",
                    onAction = vm::refresh,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    ListItem(
                        headlineContent = {
                            Text(project?.path ?: path, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (project?.isOnline == true) "开发机在线，可直接新建任务"
                                    else "开发机离线，历史对话仍可查看"
                                )
                                preset?.let { Text(presetSummary(it), style = MaterialTheme.typography.bodySmall) }
                            }
                        },
                        leadingContent = { Icon(HugeIcons.Folder01, contentDescription = null) },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (preset != null) {
                            OutlinedButton(onClick = { navController.navigate(Screen.WorkPresetEdit(id = preset.id)) }) {
                                Text("编辑预设")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    navController.navigate(
                                        Screen.WorkPresetEdit(machineId = machineId, path = project?.path ?: path)
                                    )
                                },
                            ) {
                                Text("保存为预设")
                            }
                        }
                    }
                }
                item { SectionHeader("对话与任务") }
                val sessions = project?.sessions.orEmpty()
                if (sessions.isEmpty()) {
                    item {
                        Text(
                            "这个仓库还没有会话，点右上角开始第一个任务。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(sessions, key = WorkSession::id) { session ->
                        SessionListItem(session, showRepo = false) {
                            navController.navigate(Screen.WorkflowSession(session.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    results: List<WorkSession>,
    query: String,
    contentPadding: PaddingValues,
    onOpen: (WorkSession) -> Unit,
) {
    when {
        query.isBlank() -> Box(
            Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text("输入关键词搜索会话标题、仓库路径或消息内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        results.isEmpty() -> Box(
            Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text("没有匹配的会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = WorkSession::id) { session ->
                SessionListItem(session) { onOpen(session) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun PresetRow(
    presets: List<RepoPreset>,
    onOpen: (RepoPreset) -> Unit,
    onNewTask: (RepoPreset) -> Unit,
    onCreate: () -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(presets, key = RepoPreset::id) { preset ->
            Surface(
                onClick = { onOpen(preset) },
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.width(120.dp)) {
                        Text(
                            preset.name,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(preset.agent.displayName(), "⚡完全访问".takeIf { preset.fullAccess })
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onNewTask(preset) }) {
                        Icon(HugeIcons.Add01, contentDescription = "用 ${preset.name} 新建任务")
                    }
                }
            }
        }
        item {
            Surface(onClick = onCreate, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                    Text(if (presets.isEmpty()) "添加仓库预设" else "添加")
                }
            }
        }
    }
}

@Composable
private fun SessionListItem(
    session: WorkSession,
    highlight: Boolean = false,
    showRepo: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        color = if (highlight) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        ListItem(
            headlineContent = { Text(session.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        projectName(session.path.orEmpty()).takeIf { showRepo && it.isNotBlank() },
                        session.agent.displayName(),
                        "⚡完全访问".takeIf { session.isFullAccess },
                        sessionState(session),
                        Instant.ofEpochMilli(session.updatedAt).toLocalDateTime(),
                    ).joinToString(" · ")
                )
            },
            trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
private fun WorkflowEmptyState(
    modifier: Modifier,
    title: String,
    description: String,
    action: String,
    onAction: () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HugeIcons.Folder01, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun InlineStatus(message: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

private fun presetSummary(preset: RepoPreset): String = listOfNotNull(
    preset.agent.displayName(),
    preset.model,
    preset.reasoningEffort?.let { "思考深度：${it.toReasoningLabel()}" },
    if (preset.fullAccess) "完全访问" else "普通模式",
    preset.defaultBranch?.let { "分支 $it" },
).joinToString(" · ")

internal fun WorkAgent.displayName(): String = when (this) {
    WorkAgent.CODEX -> "Codex"
    WorkAgent.CLAUDE -> "Claude Code"
    WorkAgent.OTHER -> "其他"
}

private fun sessionState(session: WorkSession): String = when {
    session.approvals.isNotEmpty() -> "等待确认"
    session.active -> "进行中"
    else -> "已结束"
}

private fun WorkSession.displayTitle(): String =
    name?.takeIf { it.isNotBlank() && !it.equals("Codex", ignoreCase = true) }
        ?: "${agent.displayName()} 对话 ${id.take(6)}"

private fun sameProjectPath(left: String, right: String): Boolean =
    left.trim().trimEnd('/', '\\').replace('\\', '/').equals(
        right.trim().trimEnd('/', '\\').replace('\\', '/'),
        ignoreCase = true,
    )
