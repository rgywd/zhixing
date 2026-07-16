package me.rerere.rikkahub.ui.pages.workflow

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyMachine
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySession
import me.rerere.rikkahub.utils.toLocalDateTime
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkflowPage(vm: WorkflowVM = koinViewModel()) {
    val navController = LocalNavController.current
    var showNewTask by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm.createdSessionId) {
        vm.createdSessionId?.let { id ->
            vm.consumeCreatedSession()
            showNewTask = false
            navController.navigate(Screen.WorkflowSession(id))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工作") },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !vm.isRefreshing) {
                        if (vm.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(HugeIcons.Refresh01, contentDescription = "刷新项目")
                        }
                    }
                    IconButton(onClick = { navController.navigate(Screen.WorkflowSettings) }) {
                        Icon(HugeIcons.Settings03, contentDescription = "工作连接设置")
                    }
                },
            )
        },
        floatingActionButton = {
            if (vm.status == WorkflowConnectionStatus.Connected && vm.machines.any { it.active }) {
                FloatingActionButton(onClick = { showNewTask = true }) {
                    Icon(HugeIcons.Add01, contentDescription = "新建 Codex 任务")
                }
            }
        },
    ) { contentPadding ->
        when {
            vm.status != WorkflowConnectionStatus.Connected -> WorkflowEmptyState(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                title = "连接开发环境后开始工作",
                description = "在设置中连接一次 Happy，之后这里会按项目保留全部 Codex 对话。",
                action = "前往连接设置",
                onAction = { navController.navigate(Screen.WorkflowSettings) },
            )
            vm.isRefreshing && vm.projects.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            vm.projects.isEmpty() -> WorkflowEmptyState(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                title = "还没有 Codex 项目",
                description = "无需先在电脑打开会话，直接从手机选择在线开发机和目录创建任务。",
                action = if (vm.machines.any { it.active }) "新建任务" else "检查开发机",
                onAction = {
                    if (vm.machines.any { it.active }) showNewTask = true
                    else navController.navigate(Screen.WorkflowSettings)
                },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                vm.syncError?.let { message -> item { InlineStatus(message, vm::refresh) } }
                item {
                    Text(
                        text = "项目",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
                items(vm.projects, key = WorkflowProject::key) { project ->
                    ProjectListItem(
                        project = project,
                        onClick = {
                            navController.navigate(Screen.WorkflowProject(project.machineId, project.path))
                        },
                    )
                }
            }
        }
    }

    if (showNewTask) {
        NewCodexTaskDialog(
            machines = vm.machines,
            initialPath = vm.projects.firstOrNull()?.path.orEmpty(),
            isSubmitting = vm.isCreatingSession,
            error = vm.createError,
            onDismiss = { if (!vm.isCreatingSession) showNewTask = false },
            onSubmit = vm::createCodexSession,
        )
    }
    vm.pendingDirectoryApproval?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::dismissDirectoryApproval,
            title = { Text("创建项目目录？") },
            text = { Text("开发机上不存在 ${pending.path}。确认后将创建目录并启动 Codex。") },
            confirmButton = { Button(onClick = vm::approveDirectoryCreation) { Text("创建并启动") } },
            dismissButton = { TextButton(onClick = vm::dismissDirectoryApproval) { Text("取消") } },
        )
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
    var showNewTask by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm.createdSessionId) {
        vm.createdSessionId?.let { id ->
            vm.consumeCreatedSession()
            showNewTask = false
            navController.navigate(Screen.WorkflowSession(id))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: projectName(path), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showNewTask = true }, enabled = project?.isOnline == true) {
                        Icon(HugeIcons.Add01, contentDescription = "在此项目中新建任务")
                    }
                },
            )
        },
    ) { contentPadding ->
        if (project == null) {
            if (vm.isRefreshing) {
                Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                WorkflowEmptyState(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    title = "项目暂时不可用",
                    description = vm.syncError ?: "项目历史可能已被删除，返回项目列表后刷新重试。",
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
                        headlineContent = { Text(project.path, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(if (project.isOnline) "开发机在线，可直接新建任务" else "开发机离线，历史对话仍可查看")
                        },
                        leadingContent = { Icon(HugeIcons.Folder01, contentDescription = null) },
                    )
                }
                item {
                    Text(
                        "对话与任务",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(project.sessions, key = HappySession::id) { session ->
                    SessionListItem(session) { navController.navigate(Screen.WorkflowSession(session.id)) }
                }
            }
        }
    }

    if (showNewTask && project != null) {
        NewCodexTaskDialog(
            machines = listOfNotNull(project.machine),
            initialPath = project.path,
            lockPath = true,
            isSubmitting = vm.isCreatingSession,
            error = vm.createError,
            onDismiss = { if (!vm.isCreatingSession) showNewTask = false },
            onSubmit = vm::createCodexSession,
        )
    }
    vm.pendingDirectoryApproval?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::dismissDirectoryApproval,
            title = { Text("创建项目目录？") },
            text = { Text("开发机上不存在 ${pending.path}。确认后将创建目录并启动 Codex。") },
            confirmButton = { Button(onClick = vm::approveDirectoryCreation) { Text("创建并启动") } },
            dismissButton = { TextButton(onClick = vm::dismissDirectoryApproval) { Text("取消") } },
        )
    }
}

@Composable
private fun ProjectListItem(project: WorkflowProject, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), tonalElevation = 1.dp) {
        ListItem(
            headlineContent = { Text(project.name, fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(project.path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${project.sessions.size} 个对话 · ${sessionState(project.latestSession)} · " +
                            Instant.ofEpochMilli(project.latestSession.updatedAt).toLocalDateTime(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            leadingContent = { Icon(HugeIcons.Folder01, contentDescription = null) },
            trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
        )
    }
}

@Composable
private fun SessionListItem(session: HappySession, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
        ListItem(
            headlineContent = { Text(session.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text("${sessionState(session)} · ${Instant.ofEpochMilli(session.updatedAt).toLocalDateTime()}")
            },
            trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
        )
    }
}

@Composable
internal fun NewCodexTaskDialog(
    machines: List<HappyMachine>,
    initialPath: String,
    lockPath: Boolean = false,
    isSubmitting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> Unit,
) {
    val onlineMachines = machines.filter { it.active && it.supportsCodex != false }
    var selectedMachineId by rememberSaveable { mutableStateOf(onlineMachines.firstOrNull()?.id.orEmpty()) }
    var path by rememberSaveable(initialPath) { mutableStateOf(initialPath) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var machineMenuExpanded by remember { mutableStateOf(false) }
    val selectedMachine = onlineMachines.firstOrNull { it.id == selectedMachineId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 Codex 任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(
                        onClick = { machineMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSubmitting,
                    ) {
                        Text(selectedMachine?.let { it.displayName ?: it.host } ?: "选择在线开发机")
                    }
                    DropdownMenu(expanded = machineMenuExpanded, onDismissRequest = { machineMenuExpanded = false }) {
                        onlineMachines.forEach { machine ->
                            DropdownMenuItem(
                                text = { Text(machine.displayName ?: machine.host) },
                                onClick = {
                                    selectedMachineId = machine.id
                                    if (path.isBlank()) path = machine.homeDir.orEmpty()
                                    machineMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("项目目录") },
                    supportingText = { Text("填写开发机上的绝对路径或 ~ 路径") },
                    singleLine = true,
                    readOnly = lockPath,
                    enabled = !isSubmitting,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("任务要求") },
                    placeholder = { Text("例如：继续 #10，先检查昨晚的执行结果") },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !isSubmitting,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(selectedMachineId, path, prompt) },
                enabled = selectedMachineId.isNotBlank() && path.isNotBlank() && prompt.isNotBlank() && !isSubmitting,
            ) {
                if (isSubmitting) CircularProgressIndicator(strokeWidth = 2.dp)
                else Text("启动任务")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("取消") } },
    )
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

private fun sessionState(session: HappySession): String = when {
    session.approvals.isNotEmpty() -> "等待确认"
    session.active -> "进行中"
    else -> "已结束"
}

private fun HappySession.displayTitle(): String =
    name?.takeIf { it.isNotBlank() && !it.equals("Codex", ignoreCase = true) }
        ?: "Codex 对话 ${id.take(6)}"

private fun sameProjectPath(left: String, right: String): Boolean =
    left.trim().trimEnd('/', '\\').replace('\\', '/').equals(
        right.trim().trimEnd('/', '\\').replace('\\', '/'),
        ignoreCase = true,
    )

private fun projectName(path: String): String =
    path.trim().trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
