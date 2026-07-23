package me.rerere.rikkahub.ui.pages.agenda

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.agenda.AgendaAction
import me.rerere.rikkahub.data.agenda.AgendaPlanProjection
import me.rerere.rikkahub.data.agenda.DeviceCalendarEvent
import me.rerere.rikkahub.data.agenda.DeviceCalendarRepository
import me.rerere.rikkahub.data.agenda.buildAgendaProjection
import me.rerere.rikkahub.data.model.AgendaPlanStageStatus
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.chat.AgendaTaskEditorSheet
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AgendaPage() {
    val taskRepository: AgendaTaskRepository = koinInject()
    val planRepository: AgendaPlanRepository = koinInject()
    val calendarRepository: DeviceCalendarRepository = koinInject()
    val navigator = LocalNavController.current
    val tasks by taskRepository.observeVisibleTasks().collectAsStateWithLifecycle(emptyList())
    val plans by planRepository.observeVisiblePlans().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val projection = remember(tasks, plans) { buildAgendaProjection(tasks, plans) }
    var editorTask by remember { mutableStateOf<AgendaTask?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var calendarAllowed by remember { mutableStateOf(calendarRepository.canRead()) }
    var calendarEvents by remember { mutableStateOf<List<DeviceCalendarEvent>>(emptyList()) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> calendarAllowed = granted || calendarRepository.canRead() }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(calendarAllowed) {
        if (!calendarAllowed) {
            calendarEvents = emptyList()
            return@LaunchedEffect
        }
        val zone = ZoneId.systemDefault()
        val begin = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now(zone).plusDays(31).atStartOfDay(zone).toInstant().toEpochMilli()
        calendarEvents = calendarRepository.getEvents(begin, end)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("我的事项")
                        Text(
                            "${projection.actions.size} 项需处理 · ${projection.upcomingPlanCount} 个计划即将到来",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { BackButton() },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editorTask = null
                editorOpen = true
            }) {
                Icon(HugeIcons.Add01, contentDescription = "新增待办")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            agendaSectionHeader("需要处理", projection.actions.size)
            if (projection.actions.isEmpty()) {
                item("no-actions") { AgendaEmptyLine("当前没有必须处理的事项") }
            } else {
                items(projection.actions, key = { it.stableId }) { action ->
                    AgendaActionRow(
                        action = action,
                        onOpen = {
                            when (action) {
                                is AgendaAction.Task -> {
                                    editorTask = action.task
                                    editorOpen = true
                                }
                                is AgendaAction.PlanStage ->
                                    navigator.navigate(Screen.AgendaPlanDetail(action.planWithStages.plan.id))
                            }
                        },
                        onToggle = {
                            scope.launch {
                                when (action) {
                                    is AgendaAction.Task -> taskRepository.setCompleted(action.task.id, true)
                                    is AgendaAction.PlanStage ->
                                        planRepository.setStageCompleted(action.stage.id, true)
                                }
                            }
                        },
                    )
                }
            }

            val upcomingCount = projection.futureTasks.size + projection.upcomingPlans.size + calendarEvents.size
            agendaSectionHeader("即将到来", upcomingCount)
            items(projection.upcomingPlans, key = { "upcoming:${it.value.plan.id}" }) { plan ->
                AgendaPlanRow(plan, onClick = { navigator.navigate(Screen.AgendaPlanDetail(plan.value.plan.id)) })
            }
            items(projection.futureTasks, key = { "future:${it.id}" }) { task ->
                AgendaTaskRow(
                    task = task,
                    onClick = {
                        editorTask = task
                        editorOpen = true
                    },
                    onToggle = { scope.launch { taskRepository.setCompleted(task.id, true) } },
                )
            }
            items(calendarEvents, key = { "calendar:${it.id}" }) { event ->
                AgendaCalendarRow(event, onClick = { calendarRepository.openEvent(event.id) })
            }
            if (!calendarAllowed) {
                item("calendar-connect") {
                    AgendaCalendarConnectRow {
                        calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                    }
                }
            }

            agendaSectionHeader("等待中", projection.waitingPlans.size)
            if (projection.waitingPlans.isEmpty()) {
                item("no-waiting") { AgendaEmptyLine("没有等待下一节点的计划") }
            } else {
                items(projection.waitingPlans, key = { "waiting:${it.value.plan.id}" }) { plan ->
                    AgendaPlanRow(plan, onClick = { navigator.navigate(Screen.AgendaPlanDetail(plan.value.plan.id)) })
                }
            }

            val completedCount = projection.completedTasks.size + projection.completedPlans.size
            agendaSectionHeader("已完成", completedCount)
            items(projection.completedPlans, key = { "completed-plan:${it.value.plan.id}" }) { plan ->
                AgendaPlanRow(plan, onClick = { navigator.navigate(Screen.AgendaPlanDetail(plan.value.plan.id)) })
            }
            items(projection.completedTasks, key = { "completed-task:${it.id}" }) { task ->
                AgendaTaskRow(
                    task = task,
                    onClick = {
                        editorTask = task
                        editorOpen = true
                    },
                    onToggle = { scope.launch { taskRepository.setCompleted(task.id, false) } },
                )
            }
            item("bottom-space") { Column(Modifier.padding(bottom = 72.dp)) {} }
        }
    }

    if (editorOpen) {
        AgendaTaskEditorSheet(
            task = editorTask,
            onDismiss = { editorOpen = false },
            onSave = { title, note, dueAt, reminderEnabled ->
                scope.launch {
                    val reminderAt = if (reminderEnabled) dueAt else null
                    if (editorTask == null) {
                        taskRepository.create(title, note, dueAt, reminderAt)
                    } else {
                        taskRepository.update(editorTask!!.id, title, note, dueAt, reminderAt)
                    }
                    if (reminderEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    editorOpen = false
                }
            },
            onDelete = editorTask?.let { task ->
                {
                    scope.launch {
                        taskRepository.delete(task.id)
                        editorOpen = false
                    }
                }
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.agendaSectionHeader(title: String, count: Int) {
    item("header:$title") {
        Text(
            "$title · $count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun AgendaActionRow(
    action: AgendaAction,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    AgendaBaseRow(
        title = action.title,
        supporting = buildString {
            action.context?.let { append(it) }
            action.actionAt?.let {
                if (isNotEmpty()) append(" · ")
                append(formatAgendaDateTime(it))
            }
            if (isEmpty()) append("随时可处理")
        },
        completed = false,
        reminder = action is AgendaAction.PlanStage && action.stage.reminderAt != null,
        onClick = onOpen,
        onToggle = onToggle,
    )
}

@Composable
private fun AgendaTaskRow(
    task: AgendaTask,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    AgendaBaseRow(
        title = task.title,
        supporting = when {
            task.status == AgendaTaskStatus.COMPLETED -> "已完成"
            task.dueAt == null -> "无截止时间"
            else -> formatAgendaDateTime(task.dueAt)
        },
        completed = task.status == AgendaTaskStatus.COMPLETED,
        reminder = task.reminderAt != null && task.status == AgendaTaskStatus.PENDING,
        onClick = onClick,
        onToggle = onToggle,
    )
}

@Composable
private fun AgendaBaseRow(
    title: String,
    supporting: String,
    completed: Boolean,
    reminder: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onToggle, modifier = Modifier.size(34.dp)) {
                Surface(
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    color = if (completed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (completed) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    if (completed) Icon(HugeIcons.Tick01, null, modifier = Modifier.padding(5.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (reminder) Icon(HugeIcons.Notification01, "已设置提醒", Modifier.size(17.dp))
        }
    }
}

@Composable
private fun AgendaPlanRow(
    projection: AgendaPlanProjection,
    onClick: () -> Unit,
) {
    val plan = projection.value
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(HugeIcons.Calendar03, null, Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    plan.plan.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        plan.plan.eventAt?.let { append(formatAgendaDateTime(it)) }
                        projection.currentStage?.let {
                            if (isNotEmpty()) append(" · ")
                            append("下一步：${it.title}")
                        }
                    }.ifBlank { "等待下一节点" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${plan.stages.count { it.status == AgendaPlanStageStatus.COMPLETED }}/${plan.stages.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AgendaCalendarRow(event: DeviceCalendarEvent, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(HugeIcons.Calendar03, null, Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    formatAgendaDateTime(event.startAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("日历", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AgendaCalendarConnectRow(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("叠加系统日历", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text("连接") }
        }
    }
}

@Composable
private fun AgendaEmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
    )
}

private fun formatAgendaDateTime(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneId.systemDefault())
    .format(AGENDA_DATE_TIME_FORMATTER)

private val AGENDA_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")
