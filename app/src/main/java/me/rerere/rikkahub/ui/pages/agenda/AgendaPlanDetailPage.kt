package me.rerere.rikkahub.ui.pages.agenda

import android.os.Build
import androidx.core.app.NotificationManagerCompat
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Location01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.data.agenda.AgendaPlanEditorSubmission
import me.rerere.rikkahub.data.agenda.currentAgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStageStatus
import me.rerere.rikkahub.data.model.AgendaPlanStatus
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

@Composable
fun AgendaPlanDetailPage(planId: String) {
    val repository: AgendaPlanRepository = koinInject()
    val navigator = LocalNavController.current
    val context = LocalContext.current
    val plans by repository.observeVisiblePlans().collectAsStateWithLifecycle(emptyList())
    val plan = remember(plans, planId) { plans.firstOrNull { it.plan.id == planId } }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val notificationPermission = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setOf(PermissionNotification)
        } else {
            emptySet()
        },
    )
    PermissionManager(notificationPermission)
    val notificationAccessGranted = notificationPermission.allPermissionsGranted &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    var showEditor by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var showCancelConfirmation by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }

    if (showEditor && plan != null) {
        AgendaPlanEditorSheet(
            initialPlan = plan,
            submitting = submitting,
            onDismiss = {
                if (!submitting) {
                    showEditor = false
                }
            },
            onSubmit = { submission ->
                if (submission is AgendaPlanEditorSubmission.Update && !submitting) {
                    submitting = true
                    scope.launch {
                        try {
                            val updated = repository.updatePlanWithStages(
                                id = submission.planId,
                                title = submission.parent.title,
                                note = submission.parent.note,
                                location = submission.parent.location,
                                eventAt = submission.parent.eventAt,
                                sourceReference = submission.parent.sourceReference,
                                stages = submission.stageUpdates.map { it.draft },
                            )
                            if (
                                updated.stages.any {
                                    it.status == AgendaPlanStageStatus.PENDING &&
                                        it.reminderAt?.let { reminderAt ->
                                            reminderAt > System.currentTimeMillis()
                                        } == true
                                } &&
                                !notificationPermission.allPermissionsGranted
                            ) {
                                notificationPermission.requestPermissions()
                            }
                            showEditor = false
                            toaster.show("计划已更新", type = ToastType.Success)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            toaster.show(
                                message = "保存计划失败：${error.message ?: "请稍后重试"}",
                                type = ToastType.Error,
                            )
                        } finally {
                            submitting = false
                        }
                    }
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = showCancelConfirmation,
        title = "取消计划？",
        confirmText = "确认取消",
        dismissText = "返回",
        onConfirm = {
            showCancelConfirmation = false
            if (!cancelling) {
                cancelling = true
                scope.launch {
                    try {
                        repository.setPlanStatus(planId, AgendaPlanStatus.CANCELLED)
                        navigator.popBackStack()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        toaster.show(
                            message = "取消计划失败：${error.message ?: "请稍后重试"}",
                            type = ToastType.Error,
                        )
                    } finally {
                        cancelling = false
                    }
                }
            }
        },
        onDismiss = { showCancelConfirmation = false },
        text = {
            Text("计划会从当前事项列表中移除，此操作不会自动完成任何阶段。")
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("计划详情") },
                navigationIcon = { BackButton() },
                actions = {
                    if (plan != null) {
                        IconButton(
                            onClick = { showEditor = true },
                            enabled = !submitting && !cancelling,
                        ) {
                            Icon(HugeIcons.PencilEdit01, "编辑计划")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (plan == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("计划不存在或已取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        val currentStage = currentAgendaPlanStage(plan)
        val hasFutureReminder = remember(plan) {
            val now = System.currentTimeMillis()
            plan.stages.any {
                it.status == AgendaPlanStageStatus.PENDING &&
                    it.reminderAt?.let { reminderAt -> reminderAt > now } == true
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("summary") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(plan.plan.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        plan.plan.eventAt?.let {
                            DetailMeta(HugeIcons.Calendar03, formatDetailDateTime(it))
                        }
                        plan.plan.location.takeIf { it.isNotBlank() }?.let {
                            DetailMeta(HugeIcons.Location01, it)
                        }
                        plan.plan.note.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            when {
                                plan.plan.status == AgendaPlanStatus.COMPLETED -> "计划已完成"
                                currentStage != null -> "当前阶段：${currentStage.title}"
                                else -> "阶段已处理，等待计划发生或手动完成"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item("stage-title") {
                Text(
                    "阶段 · ${plan.stages.count { it.status == AgendaPlanStageStatus.COMPLETED }}/${plan.stages.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (hasFutureReminder && !notificationAccessGranted) {
                item("notification-permission") {
                    PlanReminderPermissionRow(
                        onClick = {
                            if (notificationPermission.allPermissionsGranted) {
                                notificationPermission.openAppSettings()
                            } else {
                                notificationPermission.requestPermissions()
                            }
                        },
                    )
                }
            }
            items(plan.stages.sortedBy { it.position }, key = { it.id }) { stage ->
                AgendaStageRow(
                    stage = stage,
                    parentEventAt = plan.plan.eventAt,
                    current = currentStage?.id == stage.id,
                    enabled = plan.plan.status == AgendaPlanStatus.ACTIVE,
                    onToggle = {
                        scope.launch {
                            repository.setStageCompleted(
                                stage.id,
                                stage.status != AgendaPlanStageStatus.COMPLETED,
                            )
                        }
                    },
                )
            }

            item("actions") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (plan.plan.status == AgendaPlanStatus.COMPLETED) {
                        Button(
                            onClick = { scope.launch { repository.setPlanStatus(planId, AgendaPlanStatus.ACTIVE) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("重新打开")
                        }
                    } else {
                        Button(
                            onClick = { scope.launch { repository.setPlanStatus(planId, AgendaPlanStatus.COMPLETED) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("完成计划")
                        }
                        OutlinedButton(
                            onClick = { showCancelConfirmation = true },
                            enabled = !cancelling,
                        ) {
                            Text(if (cancelling) "取消中…" else "取消")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanReminderPermissionRow(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(HugeIcons.Notification01, null, Modifier.size(19.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("通知权限未开启", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "计划提醒已保存；开启后才会显示系统通知，且可能受节电策略延迟。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onClick) { Text("开启") }
        }
    }
}

@Composable
private fun DetailMeta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(17.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AgendaStageRow(
    stage: AgendaPlanStage,
    parentEventAt: Long?,
    current: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val completed = stage.status == AgendaPlanStageStatus.COMPLETED
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (current) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onToggle, enabled = enabled, modifier = Modifier.size(36.dp)) {
                Surface(
                    modifier = Modifier.size(25.dp),
                    shape = CircleShape,
                    color = if (completed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (completed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    if (completed) Icon(HugeIcons.Tick01, null, Modifier.padding(5.dp))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stage.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (current && !completed) {
                        Text("当前", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    stageTimingLabel(stage, parentEventAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (stage.reminderAt != null && !completed) {
                Icon(HugeIcons.Notification01, "已设置提醒", Modifier.size(17.dp))
            }
        }
    }
}

private fun stageTimingLabel(stage: AgendaPlanStage, eventAt: Long?): String {
    val relative = listOfNotNull(
        stage.triggerOffsetMinutes,
        stage.scheduledOffsetMinutes,
        stage.dueOffsetMinutes,
    ).firstOrNull()
    val absolute = listOfNotNull(stage.triggerAt, stage.scheduledAt, stage.dueAt).minOrNull()
    return buildString {
        relative?.let { append(relativeOffsetLabel(it)) }
        absolute?.let {
            if (isNotEmpty()) append(" · ")
            append(formatDetailDateTime(it))
        }
        if (isEmpty() && eventAt != null) append("计划发生前处理")
        if (isEmpty()) append("未设置时间")
    }
}

private fun relativeOffsetLabel(minutes: Long): String {
    if (minutes == 0L) return "计划发生时"
    val duration = Duration.ofMinutes(minutes.absoluteValue)
    val value = when {
        duration.toDays() > 0 -> "${duration.toDays()}天"
        duration.toHours() > 0 -> "${duration.toHours()}小时"
        else -> "${duration.toMinutes()}分钟"
    }
    return if (minutes < 0) "计划前$value" else "计划后$value"
}

private fun formatDetailDateTime(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneId.systemDefault())
    .format(DETAIL_DATE_TIME_FORMATTER)

private val DETAIL_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
