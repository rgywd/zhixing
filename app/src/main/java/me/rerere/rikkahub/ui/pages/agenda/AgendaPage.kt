package me.rerere.rikkahub.ui.pages.agenda

import android.Manifest
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.agenda.AgendaAction
import me.rerere.rikkahub.data.agenda.AgendaPlanProjection
import me.rerere.rikkahub.data.agenda.AgendaPlanEditorSubmission
import me.rerere.rikkahub.data.agenda.AgendaTaskSaveInput
import me.rerere.rikkahub.data.agenda.AgendaTimelineEntry
import me.rerere.rikkahub.data.agenda.AgendaTimelineTime
import me.rerere.rikkahub.data.agenda.DeviceCalendarEvent
import me.rerere.rikkahub.data.agenda.DeviceCalendarRepository
import me.rerere.rikkahub.data.agenda.buildAgendaFutureTimeline
import me.rerere.rikkahub.data.today.TodayOverviewProvider
import me.rerere.rikkahub.data.today.TodayItem
import me.rerere.rikkahub.data.agenda.resolveAgendaTaskSave
import me.rerere.rikkahub.data.model.AgendaPlanStageStatus
import me.rerere.rikkahub.data.model.AgendaRecurrence
import me.rerere.rikkahub.data.model.AgendaPlanSource
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.chat.AgendaTaskEditorSheet
import me.rerere.rikkahub.ui.pages.chat.AssistantTaskCard
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

@Composable
fun AgendaPage(initialTaskId: String? = null) {
    val taskRepository: AgendaTaskRepository = koinInject()
    val planRepository: AgendaPlanRepository = koinInject()
    val calendarRepository: DeviceCalendarRepository = koinInject()
    val todayProvider: TodayOverviewProvider = koinInject()
    val navigator = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tasks by taskRepository.observeVisibleTasks().collectAsStateWithLifecycle(emptyList())
    val plans by planRepository.observeVisiblePlans().collectAsStateWithLifecycle(emptyList())
    val todaySnapshot by todayProvider.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var lifecycleResumeRevision by remember { mutableIntStateOf(0) }
    var agendaNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val projection = todaySnapshot.agendaProjection
    val assistantTaskItems = todaySnapshot.items.filterIsInstance<TodayItem.AssistantTask>()
    val notificationPermission = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setOf(PermissionNotification)
        } else {
            emptySet()
        },
    )
    val calendarPermission = rememberPermissionState(AgendaCalendarReadPermission)
    PermissionManager(notificationPermission)
    PermissionManager(calendarPermission)

    var editorTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val editorTask = remember(tasks, editorTaskId) {
        editorTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
    }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var initialTaskHandled by rememberSaveable(initialTaskId) { mutableStateOf(false) }
    var createMenuOpen by rememberSaveable { mutableStateOf(false) }
    var planEditorOpen by rememberSaveable { mutableStateOf(false) }
    var planSubmitting by rememberSaveable { mutableStateOf(false) }
    var calendarEvents by remember { mutableStateOf<List<DeviceCalendarEvent>>(emptyList()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lifecycleResumeRevision++
                agendaNowMillis = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            delay(AGENDA_CLOCK_INTERVAL_MS - now % AGENDA_CLOCK_INTERVAL_MS)
            agendaNowMillis = System.currentTimeMillis()
        }
    }

    val calendarAllowed = calendarPermission.allPermissionsGranted && calendarRepository.canRead()
    val notificationsEnabled = remember(lifecycleResumeRevision, notificationPermission.allPermissionsGranted) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val notificationAccessGranted = notificationPermission.allPermissionsGranted && notificationsEnabled
    val hasPendingReminder = remember(tasks, plans, agendaNowMillis) {
        tasks.any {
            it.status == AgendaTaskStatus.PENDING &&
                it.reminderAt?.let { reminderAt -> reminderAt > agendaNowMillis } == true
        } ||
            plans.any { value ->
                value.stages.any { stage ->
                    stage.status == AgendaPlanStageStatus.PENDING &&
                        stage.reminderAt?.let { reminderAt -> reminderAt > agendaNowMillis } == true
                }
            }
    }
    val timeline = remember(
        projection.futureTasks,
        projection.upcomingPlans,
        calendarEvents,
        agendaNowMillis,
    ) {
        buildAgendaFutureTimeline(
            futureTasks = projection.futureTasks,
            upcomingPlans = projection.upcomingPlans,
            calendarEvents = calendarEvents,
            nowMillis = agendaNowMillis,
        )
    }

    LaunchedEffect(initialTaskId, initialTaskHandled) {
        val taskId = initialTaskId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (initialTaskHandled) return@LaunchedEffect
        initialTaskHandled = true
        val task = taskRepository.getById(taskId)
        if (task == null || task.status == AgendaTaskStatus.CANCELLED) {
            toaster.show("事项不存在或已删除")
        } else {
            editorTaskId = task.id
            editorOpen = true
        }
    }

    LaunchedEffect(calendarAllowed, lifecycleResumeRevision) {
        if (!calendarAllowed) {
            calendarEvents = emptyList()
            return@LaunchedEffect
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val begin = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        calendarEvents = calendarRepository.getEvents(begin, end)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("我的事项")
                        Text(
                            "${projection.actions.size} 项需处理 · ${projection.inboxTasks.size} 项待安排",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { BackButton() },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { createMenuOpen = true }) {
                    Icon(HugeIcons.Add01, contentDescription = "新增事项")
                }
                DropdownMenu(
                    expanded = createMenuOpen,
                    onDismissRequest = { createMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("简单待办") },
                        onClick = {
                            createMenuOpen = false
                            editorTaskId = null
                            editorOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("长期计划") },
                        onClick = {
                            createMenuOpen = false
                            planEditorOpen = true
                        },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (assistantTaskItems.isNotEmpty()) {
                agendaSectionHeader("正在处理", assistantTaskItems.size)
                items(assistantTaskItems, key = TodayItem::stableId) { taskItem ->
                    AssistantTaskCard(
                        task = taskItem.task,
                        onClick = {
                            taskItem.task.conversationId
                                ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                                ?.let { chatId ->
                                    navigateToChatPage(
                                        navigator = navigator,
                                        chatId = chatId,
                                        nodeId = taskItem.task.anchorNodeId
                                            ?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                                        preserveBackStack = true,
                                    )
                                }
                        },
                        onDismiss = { todayProvider.dismissFailedTask(taskItem.task.id) },
                    )
                }
            }

            if (hasPendingReminder && !notificationAccessGranted) {
                item("notification-permission") {
                    AgendaNotificationAccessRow(
                        onClick = {
                            if (!notificationPermission.allPermissionsGranted) {
                                notificationPermission.requestPermissions()
                            } else {
                                notificationPermission.openAppSettings()
                            }
                        },
                    )
                }
            }

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
                                    editorTaskId = action.task.id
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

            if (projection.inboxTasks.isNotEmpty()) {
                agendaSectionHeader("收件箱", projection.inboxTasks.size)
                items(projection.inboxTasks, key = { "inbox:${it.id}" }) { task ->
                    AgendaTaskRow(
                        task = task,
                        onClick = {
                            editorTaskId = task.id
                            editorOpen = true
                        },
                        onToggle = { scope.launch { taskRepository.setCompleted(task.id, true) } },
                    )
                }
            }

            agendaSectionHeader("即将到来", timeline.size)
            if (timeline.isEmpty() && calendarAllowed) {
                item("no-upcoming") { AgendaEmptyLine("未来没有已安排的事项") }
            }
            items(timeline, key = AgendaTimelineEntry::stableId) { entry ->
                when (entry) {
                    is AgendaTimelineEntry.Task -> AgendaTaskRow(
                        task = entry.task,
                        onClick = {
                            editorTaskId = entry.task.id
                            editorOpen = true
                        },
                        onToggle = { scope.launch { taskRepository.setCompleted(entry.task.id, true) } },
                    )
                    is AgendaTimelineEntry.Plan -> AgendaPlanRow(
                        projection = entry.projection,
                        onClick = {
                            navigator.navigate(Screen.AgendaPlanDetail(entry.projection.value.plan.id))
                        },
                    )
                    is AgendaTimelineEntry.Calendar -> AgendaCalendarRow(
                        event = entry.event,
                        time = entry.time,
                        onClick = { calendarRepository.openEvent(entry.event.id) },
                    )
                }
            }
            if (!calendarAllowed) {
                item("calendar-connect") {
                    AgendaCalendarConnectRow {
                        calendarPermission.requestPermissions()
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
                        editorTaskId = task.id
                        editorOpen = true
                    },
                    onToggle = { scope.launch { taskRepository.setCompleted(task.id, false) } },
                )
            }
            item("bottom-space") { Column(Modifier.padding(bottom = 72.dp)) {} }
        }
    }

    if (editorOpen && (editorTaskId == null || editorTask != null)) {
        AgendaTaskEditorSheet(
            task = editorTask,
            onDismiss = { editorOpen = false },
            onNavigateToChat = { conversationId ->
                runCatching { Uuid.parse(conversationId) }.getOrNull()?.let { chatId ->
                    navigateToChatPage(
                        navigator = navigator,
                        chatId = chatId,
                        preserveBackStack = true,
                    )
                }
            },
            onSave = { title, note, dueAt, reminderEnabled, recurrenceFrequency, recurrenceInterval ->
                val task = editorTask
                val save = resolveAgendaTaskSave(
                    AgendaTaskSaveInput(
                        title = title,
                        note = note,
                        dueAt = dueAt,
                        reminderEnabled = reminderEnabled,
                        originalDueAt = task?.dueAt,
                        originalReminderAt = task?.reminderAt,
                        quickInputEnabled = task == null,
                    )
                )
                scope.launch {
                    try {
                        val recurrence = recurrenceFrequency?.let { AgendaRecurrence(it, recurrenceInterval) }
                        if (task == null) {
                            taskRepository.create(
                                save.title,
                                save.note,
                                save.dueAt,
                                save.reminderAt,
                                recurrence = recurrence,
                            )
                        } else {
                            taskRepository.update(
                                task.id,
                                save.title,
                                save.note,
                                save.dueAt,
                                save.reminderAt,
                                recurrence,
                            )
                        }
                        if (save.reminderAt != null && !notificationPermission.allPermissionsGranted) {
                            notificationPermission.requestPermissions()
                        }
                        editorOpen = false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        toaster.show("保存事项失败：${error.message ?: "未知错误"}")
                    }
                }
            },
            onDelete = editorTask?.let { task ->
                {
                    scope.launch {
                        try {
                            taskRepository.delete(task.id)
                            editorOpen = false
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            toaster.show("删除事项失败：${error.message ?: "未知错误"}")
                        }
                    }
                }
            },
        )
    }

    if (planEditorOpen) {
        AgendaPlanEditorSheet(
            initialPlan = null,
            submitting = planSubmitting,
            onDismiss = {
                if (!planSubmitting) planEditorOpen = false
            },
            onSubmit = { submission ->
                scope.launch {
                    planSubmitting = true
                    try {
                        val savedPlan = when (submission) {
                            is AgendaPlanEditorSubmission.Create -> planRepository.create(
                                title = submission.parent.title,
                                note = submission.parent.note,
                                location = submission.parent.location,
                                eventAt = submission.parent.eventAt,
                                stages = submission.stages,
                                source = AgendaPlanSource.MANUAL,
                                sourceReference = submission.parent.sourceReference,
                            )
                            is AgendaPlanEditorSubmission.Update -> planRepository.updatePlanWithStages(
                                id = submission.planId,
                                title = submission.parent.title,
                                note = submission.parent.note,
                                location = submission.parent.location,
                                eventAt = submission.parent.eventAt,
                                sourceReference = submission.parent.sourceReference,
                                stages = submission.stageUpdates.map { it.draft },
                            )
                        }
                        if (
                            savedPlan.stages.any {
                                it.status == AgendaPlanStageStatus.PENDING &&
                                    it.reminderAt?.let { reminderAt ->
                                        reminderAt > System.currentTimeMillis()
                                    } == true
                            } &&
                            !notificationPermission.allPermissionsGranted
                        ) {
                            notificationPermission.requestPermissions()
                        }
                        planEditorOpen = false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        toaster.show("保存长期计划失败：${error.message ?: "未知错误"}")
                    } finally {
                        planSubmitting = false
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
                        projection.nextAt?.let { append(formatAgendaDateTime(it)) }
                            ?: plan.plan.eventAt?.let { append(formatAgendaDateTime(it)) }
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
private fun AgendaCalendarRow(
    event: DeviceCalendarEvent,
    time: AgendaTimelineTime,
    onClick: () -> Unit,
) {
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
                    formatAgendaTimelineTime(time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("日历", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AgendaNotificationAccessRow(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(HugeIcons.Notification01, null, Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("提醒通知尚未开启", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "提醒已保存，但系统不会弹出通知；开启后仍可能受系统节电策略延迟。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onClick) { Text("开启") }
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

private fun formatAgendaTimelineTime(value: AgendaTimelineTime): String = when (value) {
    is AgendaTimelineTime.Timed -> formatAgendaDateTime(value.startAt)
    is AgendaTimelineTime.AllDay -> {
        val start = value.startDate.format(AGENDA_DATE_FORMATTER)
        val lastDay = value.endDateExclusive.minusDays(1)
        if (lastDay == value.startDate) {
            "$start 全天"
        } else {
            "$start–${lastDay.format(AGENDA_DATE_FORMATTER)} 全天"
        }
    }
}

private val AgendaCalendarReadPermission = PermissionInfo(
    permission = Manifest.permission.READ_CALENDAR,
    displayName = { Text("读取系统日历") },
    usage = { Text("用于在事项时间线中只读叠加未来 7 天的系统日历。") },
    required = true,
)

private val AGENDA_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val AGENDA_DATE_FORMATTER = DateTimeFormatter.ofPattern("M月d日")
private const val AGENDA_CLOCK_INTERVAL_MS = 60_000L
