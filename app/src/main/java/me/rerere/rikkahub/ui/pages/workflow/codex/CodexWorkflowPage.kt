package me.rerere.rikkahub.ui.pages.workflow.codex

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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
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
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.workflow.codex.CodexProject
import me.rerere.rikkahub.data.workflow.codex.CodexRuntimeState
import me.rerere.rikkahub.data.workflow.codex.CodexThread
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

@Composable
fun CodexWorkflowPage(vm: CodexWorkflowVM = koinViewModel()) {
    val navController = LocalNavController.current
    var searchActive by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            if (searchActive) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { searchActive = false; vm.clearSearch() }) {
                            Icon(HugeIcons.ArrowRight01, "退出搜索", Modifier.rotate(180f))
                        }
                    },
                    title = {
                        OutlinedTextField(
                            value = vm.searchQuery,
                            onValueChange = vm::updateSearchQuery,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索项目、任务或内容") },
                            singleLine = true,
                        )
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("工作") },
                    navigationIcon = { BackButton() },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(HugeIcons.Search01, "搜索 Codex 项目与任务")
                        }
                        IconButton(onClick = vm::refresh, enabled = !vm.isRefreshing) {
                            if (vm.isRefreshing) CircularProgressIndicator(Modifier.padding(12.dp), strokeWidth = 2.dp)
                            else Icon(HugeIcons.Refresh01, "同步")
                        }
                        IconButton(onClick = { navController.navigate(Screen.CodexWorkflowSettings) }) {
                            Icon(HugeIcons.Settings03, "开发环境设置")
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (searchActive) {
            CodexSearchResults(vm.searchQuery, vm.searchResults, padding) { thread ->
                navController.navigate(Screen.CodexThread(thread.machineId, thread.threadId))
            }
        } else {
            CodexHomeContent(vm, padding)
        }
    }
}

@Composable
private fun CodexHomeContent(vm: CodexWorkflowVM, padding: PaddingValues) {
    val navController = LocalNavController.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding + PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        vm.statusMessage?.let { message -> item { OfflineStatus(message, vm::refresh) } }
        if (vm.needsAttention.isNotEmpty()) {
            item { SectionLabel("需要处理 · ${vm.needsAttention.size}") }
            items(vm.needsAttention.take(3), key = { "attention-${it.machineId}-${it.threadId}" }) { thread ->
                CodexThreadRow(thread, emphasized = true) {
                    navController.navigate(Screen.CodexThread(thread.machineId, thread.threadId))
                }
            }
        }
        item { SectionLabel("项目") }
        items(vm.projects, key = CodexProject::projectId) { project ->
            ProjectCard(project) { navController.navigate(Screen.CodexProject(project.projectId)) }
        }
        if (vm.projects.isEmpty()) {
            item {
                EmptyCatalog(
                    connected = vm.isConnected,
                    onSettings = { navController.navigate(Screen.CodexWorkflowSettings) },
                    onRefresh = vm::refresh,
                )
            }
        }
    }
}

@Composable
fun CodexProjectPage(projectId: String, vm: CodexWorkflowVM = koinViewModel()) {
    val navController = LocalNavController.current
    var filter by rememberSaveable { mutableStateOf(ProjectFilter.ALL) }
    var showNewTask by rememberSaveable { mutableStateOf(false) }
    var newTaskText by rememberSaveable { mutableStateOf("") }
    val project = vm.project(projectId)
    LaunchedEffect(vm.newThreadTarget) {
        vm.newThreadTarget?.let { (machineId, threadId) ->
            vm.consumeNewThreadTarget()
            navController.navigate(Screen.CodexThread(machineId, threadId))
        }
    }
    if (showNewTask && project != null) {
        AlertDialog(
            onDismissRequest = { if (!vm.isStartingTask) showNewTask = false },
            title = { Text("在 ${project.displayName} 新建任务") },
            text = {
                OutlinedTextField(
                    value = newTaskText,
                    onValueChange = { newTaskText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("描述要完成的工作") },
                    minLines = 3,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.startTask(project, newTaskText); showNewTask = false; newTaskText = "" },
                    enabled = newTaskText.isNotBlank() && !vm.isStartingTask,
                ) { Text("开始") }
            },
            dismissButton = { TextButton(onClick = { showNewTask = false }) { Text("取消") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.displayName ?: "项目", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton() },
            )
        },
        floatingActionButton = {
            if (project != null) {
                FloatingActionButton(onClick = { showNewTask = true }, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Text("＋ 新建")
                }
            }
        },
    ) { padding ->
        if (project == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("项目不在当前缓存中", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        val threads = project.primaryThreads.filter { filter.matches(it) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            vm.statusMessage?.let { message -> item { OfflineStatus(message, vm::refresh) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectFilter.entries.forEach { value ->
                        FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value.label) })
                    }
                }
            }
            items(threads, key = { "${it.machineId}-${it.threadId}" }) { thread ->
                CodexThreadRow(
                    thread = thread,
                    subagentCount = project.threads.count { it.isSubagent && it.parentThreadId == thread.threadId },
                ) {
                    navController.navigate(Screen.CodexThread(thread.machineId, thread.threadId))
                }
            }
            if (threads.isEmpty()) item { Text("这个筛选下没有任务", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (project.automations.isNotEmpty()) {
                item { SectionLabel("自动化 · ${project.automations.size}") }
                items(project.automations, key = { "automation-${it.machineId}-${it.threadId}" }) { thread ->
                    CodexThreadRow(thread) {
                        navController.navigate(Screen.CodexThread(thread.machineId, thread.threadId))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: CodexProject, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
        ListItem(
            headlineContent = { Text(project.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        project.machine?.displayName,
                        "${project.primaryThreads.size} 个任务",
                        "${project.needsAttention} 个待处理".takeIf { project.needsAttention > 0 },
                        project.branch,
                    ).joinToString(" · "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = { Icon(HugeIcons.Folder01, null) },
            trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
        )
    }
}

@Composable
private fun CodexThreadRow(
    thread: CodexThread,
    emphasized: Boolean = false,
    subagentCount: Int = 0,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (emphasized) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        ListItem(
            headlineContent = { Text(thread.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        runtimeLabel(thread.runtimeState),
                        "$subagentCount 个子任务".takeIf { subagentCount > 0 },
                        Instant.ofEpochMilli(thread.recencyAt).toLocalDateTime(),
                    )
                        .joinToString(" · "),
                    maxLines = 1,
                )
            },
            trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
        )
    }
}

@Composable
private fun CodexSearchResults(
    query: String,
    results: List<CodexThread>,
    padding: PaddingValues,
    onOpen: (CodexThread) -> Unit,
) {
    if (query.isBlank() || results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(if (query.isBlank()) "输入关键词搜索" else "没有匹配结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results, key = { "search-${it.machineId}-${it.threadId}" }) { CodexThreadRow(it) { onOpen(it) } }
        }
    }
}

@Composable
private fun OfflineStatus(message: String, onRetry: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun EmptyCatalog(connected: Boolean, onSettings: () -> Unit, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(HugeIcons.Folder01, null, tint = MaterialTheme.colorScheme.primary)
        Text(if (connected) "还没有同步到 Codex 项目" else "连接开发环境后查看 Codex 项目")
        Button(onClick = if (connected) onRefresh else onSettings) { Text(if (connected) "同步" else "前往设置") }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(8.dp, 6.dp))
}

private enum class ProjectFilter(val label: String) {
    ALL("全部"), RUNNING("进行中"), COMPLETED("已完成"), ARCHIVED("已归档");
    fun matches(thread: CodexThread): Boolean = when (this) {
        ALL -> !thread.archived
        RUNNING -> !thread.archived && thread.runtimeState in setOf(
            CodexRuntimeState.RUNNING,
            CodexRuntimeState.WAITING_APPROVAL,
            CodexRuntimeState.WAITING_USER,
        )
        COMPLETED -> !thread.archived && thread.runtimeState in setOf(CodexRuntimeState.IDLE, CodexRuntimeState.UNKNOWN)
        ARCHIVED -> thread.archived
    }
}

private fun runtimeLabel(state: CodexRuntimeState): String = when (state) {
    CodexRuntimeState.RUNNING -> "进行中"
    CodexRuntimeState.WAITING_APPROVAL -> "等待确认"
    CodexRuntimeState.WAITING_USER -> "等你回复"
    CodexRuntimeState.IDLE -> "已完成"
    CodexRuntimeState.SYSTEM_ERROR -> "异常"
    CodexRuntimeState.UNKNOWN -> "历史任务"
}
