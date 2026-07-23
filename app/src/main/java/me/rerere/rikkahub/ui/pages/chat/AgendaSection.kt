package me.rerere.rikkahub.ui.pages.chat

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Task01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.data.agenda.DeviceCalendarEvent
import me.rerere.rikkahub.data.agenda.DeviceCalendarRepository
import me.rerere.rikkahub.data.agenda.AgendaAction
import me.rerere.rikkahub.data.agenda.AgendaTaskBucket
import me.rerere.rikkahub.data.agenda.agendaTaskBucket
import me.rerere.rikkahub.data.agenda.buildAgendaProjection
import me.rerere.rikkahub.data.agenda.parseAgendaQuickInput
import me.rerere.rikkahub.data.model.AgendaPlanStageStatus
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun AgendaOverviewSection() {
    val repository: AgendaTaskRepository = koinInject()
    val planRepository: AgendaPlanRepository = koinInject()
    val navigator = LocalNavController.current
    val tasks by repository.observeVisibleTasks().collectAsStateWithLifecycle(emptyList())
    val plans by planRepository.observeVisiblePlans().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editorTask by remember { mutableStateOf<AgendaTask?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val projection = remember(tasks, plans) { buildAgendaProjection(tasks, plans) }
    val previewPlan = remember(projection) {
        (projection.waitingPlans + projection.upcomingPlans).minByOrNull { it.nextAt ?: Long.MAX_VALUE }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("我的事项", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${projection.actions.size} 项需处理 · ${projection.upcomingPlanCount} 个计划即将到来",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                editorTask = null
                editorOpen = true
            }) {
                Icon(HugeIcons.Add01, contentDescription = "新增待办")
            }
        }

        projection.actions.take(2).forEach { action ->
            CompactAgendaActionCard(
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
                onComplete = {
                    scope.launch {
                        when (action) {
                            is AgendaAction.Task -> repository.setCompleted(action.task.id, true)
                            is AgendaAction.PlanStage -> planRepository.setStageCompleted(action.stage.id, true)
                        }
                    }
                },
            )
        }

        if (previewPlan != null) {
            CompactAgendaPlanCard(
                plan = previewPlan.value,
                onClick = { navigator.navigate(Screen.AgendaPlanDetail(previewPlan.value.plan.id)) },
            )
        }

        if (projection.actions.isEmpty() && previewPlan == null) {
            Text(
                "当前没有需要处理的事项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }

        TextButton(
            onClick = { navigator.navigate(Screen.Agenda) },
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("查看全部")
        }
    }

    if (editorOpen) {
        AgendaTaskEditorSheet(
            task = editorTask,
            onDismiss = { editorOpen = false },
            onSave = { title, note, dueAt, reminderEnabled ->
                scope.launch {
                    val parsed = if (dueAt == null) parseAgendaQuickInput(title) else null
                    val finalTitle = parsed?.title?.ifBlank { title } ?: title
                    val finalDueAt = dueAt ?: parsed?.dueAt
                    val reminderAt = if (reminderEnabled) finalDueAt else null
                    if (editorTask == null) {
                        repository.create(finalTitle, note, finalDueAt, reminderAt)
                    } else {
                        repository.update(editorTask!!.id, finalTitle, note, finalDueAt, reminderAt)
                    }
                    if (
                        reminderEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    editorOpen = false
                }
            },
            onDelete = editorTask?.let { task ->
                {
                    scope.launch {
                        repository.delete(task.id)
                        editorOpen = false
                    }
                }
            },
        )
    }
}

@Composable
private fun CompactAgendaActionCard(
    action: AgendaAction,
    onOpen: () -> Unit,
    onComplete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onComplete, modifier = Modifier.size(34.dp)) {
                Surface(
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {}
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    action.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    compactActionLabel(action),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (action.dueAt != null && action.dueAt!! < System.currentTimeMillis()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CompactAgendaPlanCard(
    plan: AgendaPlanWithStages,
    onClick: () -> Unit,
) {
    val current = plan.stages.firstOrNull { it.status == AgendaPlanStageStatus.PENDING }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(HugeIcons.Calendar03, contentDescription = null, modifier = Modifier.size(20.dp))
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
                        plan.plan.eventAt?.let { append(formatTaskDateTime(it)) }
                        current?.let {
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

private fun compactActionLabel(action: AgendaAction): String = buildString {
    action.context?.let { append(it) }
    action.actionAt?.let {
        if (isNotEmpty()) append(" · ")
        append(formatTaskDateTime(it))
    }
    if (isEmpty()) append("随时可处理")
}

@Composable
private fun AgendaGroupBlock(
    group: AgendaGroup,
    onTaskClick: (AgendaTask) -> Unit,
    onToggleTask: (AgendaTask) -> Unit,
    onCalendarClick: (DeviceCalendarEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            group.kind.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (group.kind == AgendaGroupKind.OVERDUE) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        group.entries.forEach { entry ->
            when (entry) {
                is AgendaEntry.Task -> TaskAgendaCard(entry.value, onTaskClick, onToggleTask)
                is AgendaEntry.Calendar -> CalendarAgendaCard(entry.value, onCalendarClick)
            }
        }
    }
}

@Composable
private fun TaskAgendaCard(
    task: AgendaTask,
    onClick: (AgendaTask) -> Unit,
    onToggle: (AgendaTask) -> Unit,
) {
    val completed = task.status == AgendaTaskStatus.COMPLETED
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick(task) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = { onToggle(task) }) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = CircleShape,
                    color = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (completed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    if (completed) {
                        Icon(HugeIcons.Tick01, contentDescription = "恢复待办", modifier = Modifier.padding(6.dp))
                    }
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    taskTimeLabel(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOverdue(task)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.reminderAt != null && !completed) {
                Icon(HugeIcons.Notification01, contentDescription = "已设置提醒", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CalendarAgendaCard(
    event: DeviceCalendarEvent,
    onClick: (DeviceCalendarEvent) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick(event) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HugeIcons.Calendar03, contentDescription = null, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(event.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    calendarTimeLabel(event),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("日历", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EmptyAgendaCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(HugeIcons.Task01, contentDescription = null, modifier = Modifier.size(28.dp))
            Text("这里还没有事项", style = MaterialTheme.typography.titleSmall)
            Text(
                "点右上角添加，或直接在聊天里告诉 AI",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CalendarPermissionCard(onConnect: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(HugeIcons.Calendar03, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text("叠加系统日历", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("可选；不授权也能正常使用待办", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onConnect) { Text("连接") }
        }
    }
}

@Composable
internal fun AgendaTaskEditorSheet(
    task: AgendaTask?,
    onDismiss: () -> Unit,
    onSave: (title: String, note: String, dueAt: Long?, reminderEnabled: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var title by remember(task?.id) { mutableStateOf(task?.title.orEmpty()) }
    var note by remember(task?.id) { mutableStateOf(task?.note.orEmpty()) }
    var dueAt by remember(task?.id) { mutableStateOf(task?.dueAt) }
    var reminderEnabled by remember(task?.id) { mutableStateOf(task?.reminderAt != null) }
    val reminderEligible = dueAt?.let { it > System.currentTimeMillis() } == true

    fun pickTime() {
        val initial = dueAt?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: ZonedDateTime.now(zone).plusHours(1)
        DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        dueAt = ZonedDateTime.of(year, month + 1, day, hour, minute, 0, 0, zone)
                            .toInstant().toEpochMilli()
                    },
                    initial.hour,
                    initial.minute,
                    true,
                ).show()
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).show()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (task == null) "新增待办" else "编辑待办", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("要做什么") },
                placeholder = { Text("例如：明天 20:00 给父母打电话") },
                minLines = 2,
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注（可选）") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = ::pickTime, modifier = Modifier.weight(1f)) {
                    Text(dueAt?.let(::formatTaskDateTime) ?: "添加时间")
                }
                if (dueAt != null) {
                    TextButton(onClick = {
                        dueAt = null
                        reminderEnabled = false
                    }) { Text("清除") }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("到点提醒", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            dueAt == null -> "设置时间后可开启"
                            !reminderEligible -> "提醒时间需要晚于现在"
                            else -> "使用系统通知提醒"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = reminderEnabled,
                    onCheckedChange = { reminderEnabled = it },
                    enabled = reminderEligible,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                onDelete?.let {
                    OutlinedButton(onClick = it) {
                        Icon(HugeIcons.Delete01, contentDescription = null)
                        Text("删除")
                    }
                }
                Button(
                    onClick = { onSave(title, note, dueAt, reminderEnabled && reminderEligible) },
                    modifier = Modifier.weight(1f),
                    enabled = title.isNotBlank(),
                ) { Text("保存") }
            }
            Text(
                "聊天中创建待办时，AI 也会使用同一份本地数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

private enum class AgendaGroupKind(val label: String) {
    OVERDUE("逾期"),
    TODAY("今天"),
    UPCOMING("接下来"),
    COMPLETED("已完成"),
}

private sealed interface AgendaEntry {
    val sortAt: Long

    data class Task(val value: AgendaTask) : AgendaEntry {
        override val sortAt: Long = value.dueAt ?: Long.MIN_VALUE
    }

    data class Calendar(val value: DeviceCalendarEvent) : AgendaEntry {
        override val sortAt: Long = value.startAt
    }
}

private data class AgendaGroup(
    val kind: AgendaGroupKind,
    val entries: List<AgendaEntry>,
)

private fun buildAgendaGroups(
    tasks: List<AgendaTask>,
    events: List<DeviceCalendarEvent>,
    now: ZonedDateTime = ZonedDateTime.now(),
): List<AgendaGroup> {
    val zone = now.zone
    val todayStart = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    val tomorrowStart = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val horizon = now.toLocalDate().plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
    val completed = tasks.filter { agendaTaskBucket(it, now) == AgendaTaskBucket.COMPLETED }.map(AgendaEntry::Task)
    val overdue = tasks.filter { agendaTaskBucket(it, now) == AgendaTaskBucket.OVERDUE }.map(AgendaEntry::Task)
    val todayTasks = tasks.filter { agendaTaskBucket(it, now) == AgendaTaskBucket.TODAY }.map(AgendaEntry::Task)
    val upcomingTasks = tasks.filter { agendaTaskBucket(it, now) == AgendaTaskBucket.UPCOMING }.map(AgendaEntry::Task)
    val todayEvents = events.filter { it.startAt < tomorrowStart && it.endAt > todayStart }.map(AgendaEntry::Calendar)
    val upcomingEvents = events.filter { it.startAt in tomorrowStart until horizon }.map(AgendaEntry::Calendar)
    return listOf(
        AgendaGroup(AgendaGroupKind.OVERDUE, overdue.sortedBy { it.sortAt }),
        AgendaGroup(AgendaGroupKind.TODAY, (todayTasks + todayEvents).sortedBy { it.sortAt }),
        AgendaGroup(AgendaGroupKind.UPCOMING, (upcomingTasks + upcomingEvents).sortedBy { it.sortAt }),
        AgendaGroup(AgendaGroupKind.COMPLETED, completed.sortedByDescending { it.value.completedAt }),
    ).filter { it.entries.isNotEmpty() }
}

private fun isOverdue(task: AgendaTask): Boolean =
    task.status == AgendaTaskStatus.PENDING && task.dueAt != null && task.dueAt < System.currentTimeMillis()

private fun taskTimeLabel(task: AgendaTask): String = when {
    task.status == AgendaTaskStatus.COMPLETED -> "已完成"
    task.dueAt == null -> "无截止时间"
    isOverdue(task) -> "${formatTaskDateTime(task.dueAt)} · 已逾期"
    else -> formatTaskDateTime(task.dueAt)
}

private fun calendarTimeLabel(event: DeviceCalendarEvent): String {
    if (event.allDay) return "全天${event.location.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(event.startAt).atZone(zone)
    val end = Instant.ofEpochMilli(event.endAt).atZone(zone)
    val time = if (start.toLocalDate() == LocalDate.now(zone)) {
        "${start.format(TIME_FORMATTER)}–${end.format(TIME_FORMATTER)}"
    } else {
        "${start.format(DATE_TIME_FORMATTER)}–${end.format(TIME_FORMATTER)}"
    }
    return "$time${event.location.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
}

private fun formatTaskDateTime(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneId.systemDefault())
    .format(DATE_TIME_FORMATTER)

private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
