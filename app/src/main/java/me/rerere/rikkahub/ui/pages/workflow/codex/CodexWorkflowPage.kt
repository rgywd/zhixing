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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
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
    var showProjectManager by rememberSaveable { mutableStateOf(false) }
    if (showProjectManager) {
        ProjectManagementSheet(
            projects = vm.projects,
            homeProjectIds = vm.homeCatalog.projects.mapTo(hashSetOf(), CodexProject::projectId),
            onDismiss = { showProjectManager = false },
            onOpenProject = { project ->
                showProjectManager = false
                navController.navigate(Screen.CodexProject(project.projectId))
            },
            onPin = vm::setProjectPinned,
            onHide = vm::setProjectHidden,
        )
    }
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
            CodexHomeContent(vm, padding, onManageProjects = { showProjectManager = true })
        }
    }
}

@Composable
private fun CodexHomeContent(vm: CodexWorkflowVM, padding: PaddingValues, onManageProjects: () -> Unit) {
    val navController = LocalNavController.current
    val home = vm.homeCatalog
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
        if (home.pinnedThreads.isNotEmpty()) {
            item { SectionLabel("重点对话 · ${home.pinnedThreads.size}") }
            items(home.pinnedThreads.take(5), key = { "pinned-${it.machineId}-${it.threadId}" }) { thread ->
                CodexThreadRow(thread, onPin = { vm.setThreadPinned(thread, !thread.isPinned) }) {
                    navController.navigate(Screen.CodexThread(thread.machineId, thread.threadId))
                }
            }
            if (home.pinnedThreads.size > 5) {
                item {
                    Text(
                        "还有 ${home.pinnedThreads.size - 5} 个重点对话，可通过搜索或对应项目查看",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("项目 · ${home.projects.size}", Modifier.weight(1f))
                TextButton(onClick = onManageProjects) { Text("管理全部 ${vm.projects.size}") }
            }
        }
        items(home.projects, key = CodexProject::projectId) { project ->
            ProjectCard(
                project = project,
                onClick = { navController.navigate(Screen.CodexProject(project.projectId)) },
                onPin = { vm.setProjectPinned(project, !project.isPinned) },
                onHide = { vm.setProjectHidden(project, true) },
            )
        }
        if (home.remainingProjectCount > 0) {
            item {
                OutlinedButton(onClick = onManageProjects, modifier = Modifier.fillMaxWidth()) {
                    Text("查看另外 ${home.remainingProjectCount} 个项目")
                }
            }
        }
        if (home.projects.isEmpty()) {
            item {
                EmptyCatalog(
                    connected = vm.isConnected,
                    hasHistory = vm.projects.isNotEmpty(),
                    onSettings = { navController.navigate(Screen.CodexWorkflowSettings) },
                    onRefresh = vm::refresh,
                    onManage = onManageProjects,
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
    var showProjectMenu by remember { mutableStateOf(false) }
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
                actions = {
                    if (project != null) {
                        Box {
                            IconButton(onClick = { showProjectMenu = true }) {
                                Icon(HugeIcons.MoreVertical, "管理项目")
                            }
                            DropdownMenu(expanded = showProjectMenu, onDismissRequest = { showProjectMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (project.isPinned) "取消置顶" else "置顶到工作主页") },
                                    leadingIcon = {
                                        Icon(if (project.isPinned) HugeIcons.PinOff else HugeIcons.Pin, null)
                                    },
                                    onClick = {
                                        vm.setProjectPinned(project, !project.isPinned)
                                        showProjectMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (project.isHidden) "显示在工作主页" else "从工作主页隐藏") },
                                    onClick = {
                                        if (project.isHidden) vm.setProjectPinned(project, true)
                                        else vm.setProjectHidden(project, true)
                                        showProjectMenu = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (project?.existsOnDisk == true) {
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
        val automations = project.automations.filter { filter.matches(it) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            vm.statusMessage?.let { message -> item { OfflineStatus(message, vm::refresh) } }
            if (!project.existsOnDisk) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            "开发机上的项目目录已不存在。历史仍可查看，但不能在这里新建任务。",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ProjectFilter.entries) { value ->
                        FilterChip(
                            selected = filter == value,
                            onClick = { filter = value },
                            label = { Text(value.label) },
                        )
                    }
                }
            }
            items(threads, key = { "${it.machineId}-${it.threadId}" }) { thread ->
                CodexThreadRow(
                    thread = thread,
                    subagentCount = project.threads.count { it.isSubagent && it.parentThreadId == thread.threadId },
                    onPin = { vm.setThreadPinned(thread, !thread.isPinned) },
                ) {
                    navController.navigate(Screen.CodexThread(thread.machineId, thread.threadId))
                }
            }
            if (threads.isEmpty()) item { Text("这个筛选下没有任务", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (automations.isNotEmpty()) {
                item { SectionLabel("自动化 · ${automations.size}") }
                items(automations, key = { "automation-${it.machineId}-${it.threadId}" }) { thread ->
                    CodexThreadRow(thread, onPin = { vm.setThreadPinned(thread, !thread.isPinned) }) {
                        navController.navigate(Screen.CodexThread(thread.machineId, thread.threadId))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: CodexProject,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
        ListItem(
            headlineContent = { Text(project.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        project.machine?.displayName,
                        "目录已不存在".takeIf { !project.existsOnDisk },
                        "${project.currentThreads.size} 个当前任务",
                        "${project.archivedThreads.size} 个归档".takeIf { project.archivedThreads.isNotEmpty() },
                        "${project.needsAttention} 个待处理".takeIf { project.needsAttention > 0 },
                        project.branch,
                    ).joinToString(" · "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Icon(if (project.isPinned) HugeIcons.Pin else HugeIcons.Folder01, null)
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(HugeIcons.MoreVertical, "管理 ${project.displayName}")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (project.isPinned) "取消置顶" else "置顶") },
                            leadingIcon = { Icon(if (project.isPinned) HugeIcons.PinOff else HugeIcons.Pin, null) },
                            onClick = { onPin(); showMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("从工作主页隐藏") },
                            onClick = { onHide(); showMenu = false },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun CodexThreadRow(
    thread: CodexThread,
    emphasized: Boolean = false,
    subagentCount: Int = 0,
    onPin: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
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
                        "已归档".takeIf { thread.archived },
                        runtimeLabel(thread.runtimeState),
                        "$subagentCount 个子任务".takeIf { subagentCount > 0 },
                        Instant.ofEpochMilli(thread.recencyAt).toLocalDateTime(),
                    )
                        .joinToString(" · "),
                    maxLines = 1,
                )
            },
            trailingContent = {
                if (onPin == null) {
                    Icon(HugeIcons.ArrowRight01, null)
                } else {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(if (thread.isPinned) HugeIcons.Pin else HugeIcons.MoreVertical, "管理对话")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (thread.isPinned) "取消重点" else "设为重点") },
                                leadingIcon = {
                                    Icon(if (thread.isPinned) HugeIcons.PinOff else HugeIcons.Pin, null)
                                },
                                onClick = { onPin(); showMenu = false },
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ProjectManagementSheet(
    projects: List<CodexProject>,
    homeProjectIds: Set<String>,
    onDismiss: () -> Unit,
    onOpenProject: (CodexProject) -> Unit,
    onPin: (CodexProject, Boolean) -> Unit,
    onHide: (CodexProject, Boolean) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(ProjectManagementFilter.ALL) }
    val filteredProjects = projects
        .filter { project -> filter.matches(project, homeProjectIds) }
        .sortedWith(
            compareByDescending<CodexProject> { it.projectId in homeProjectIds }
                .thenByDescending(CodexProject::isPinned)
                .thenByDescending(CodexProject::activeAt)
        )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("管理项目", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "主页只展示置顶和最近使用的项目。隐藏或收起只影响这台手机，不会删除开发机目录和 Codex 历史。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ProjectManagementFilter.entries) { value ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { filter = value },
                        label = {
                            val count = projects.count { value.matches(it, homeProjectIds) }
                            Text("${value.label} $count")
                        },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredProjects, key = { "manage-${it.projectId}" }) { project ->
                    val isOnHome = project.projectId in homeProjectIds
                    Surface(
                        onClick = { onOpenProject(project) },
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 1.dp,
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(project.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        "主页".takeIf { isOnHome },
                                        "已隐藏".takeIf { project.isHidden },
                                        "目录已不存在".takeIf { !project.existsOnDisk },
                                        "${project.currentThreads.size} 个当前任务".takeIf {
                                            project.currentThreads.isNotEmpty()
                                        },
                                        "仅归档 ${project.archivedThreads.size} 个".takeIf {
                                            project.currentThreads.isEmpty() && project.archivedThreads.isNotEmpty()
                                        },
                                        project.machine?.displayName,
                                    ).joinToString(" · "),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(if (project.isPinned) HugeIcons.Pin else HugeIcons.Folder01, null)
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { onPin(project, !project.isPinned) }) {
                                        Icon(
                                            if (project.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                                            if (project.isPinned) "取消置顶" else "置顶",
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (isOnHome) onHide(project, true) else onPin(project, true)
                                        }
                                    ) {
                                        Icon(
                                            if (isOnHome) HugeIcons.ViewOff else HugeIcons.View,
                                            if (isOnHome) "从主页隐藏" else "显示在主页",
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                if (filteredProjects.isEmpty()) {
                    item {
                        Text(
                            "这里还没有项目",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    }
                }
            }
        }
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
private fun EmptyCatalog(
    connected: Boolean,
    hasHistory: Boolean,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onManage: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(HugeIcons.Folder01, null, tint = MaterialTheme.colorScheme.primary)
        Text(
            when {
                hasHistory -> "当前没有需要放在主页的项目"
                connected -> "还没有同步到 Codex 项目"
                else -> "连接开发环境后查看 Codex 项目"
            }
        )
        Button(
            onClick = when {
                hasHistory -> onManage
                connected -> onRefresh
                else -> onSettings
            }
        ) {
            Text(
                when {
                    hasHistory -> "管理全部项目"
                    connected -> "同步"
                    else -> "前往设置"
                }
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(8.dp, 6.dp),
    )
}

private enum class ProjectFilter(val label: String) {
    ALL("当前"), RUNNING("进行中"), COMPLETED("已完成"), ARCHIVED("已归档");
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

private enum class ProjectManagementFilter(val label: String) {
    ALL("全部"), HOME("主页"), OUTSIDE_HOME("未在主页");

    fun matches(project: CodexProject, homeProjectIds: Set<String>): Boolean = when (this) {
        ALL -> true
        HOME -> project.projectId in homeProjectIds
        OUTSIDE_HOME -> project.projectId !in homeProjectIds
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
