package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.status.MyStatusCoordinator
import me.rerere.rikkahub.data.status.MyStatusSnapshot
import me.rerere.rikkahub.data.status.buildMyStatusRuntimeContext
import me.rerere.rikkahub.data.today.TodayOverviewProvider
import me.rerere.rikkahub.data.today.TodayItem
import me.rerere.rikkahub.data.db.entity.AssistantTaskEntity
import me.rerere.rikkahub.data.task.AssistantTaskStatus
import me.rerere.rikkahub.data.work.PhoneWorkSession
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 空会话态的统一入口:Work、事项行动与当前状态摘要。
 * Work 始终存在，但继续使用独立的 Phone-line 运行时和页面。
 */
@Composable
internal fun TodayOverviewCards(
    onOpenAgenda: () -> Unit,
    modifier: Modifier = Modifier,
    provider: TodayOverviewProvider = koinInject(),
) {
    val snapshot by provider.state.collectAsStateWithLifecycle()
    LaunchedEffect(provider) { provider.onVisible() }
    val navigator = LocalNavController.current
    val uriHandler = LocalUriHandler.current
    var showCompleted by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        snapshot.items.filterIsInstance<TodayItem.AssistantTask>().forEach { item ->
            AssistantTaskCard(
                task = item.task,
                onClick = {
                    item.task.conversationId?.let { conversationId ->
                        runCatching { Uuid.parse(conversationId) }.getOrNull()?.let { chatId ->
                            navigateToChatPage(
                                navigator = navigator,
                                chatId = chatId,
                                nodeId = item.task.anchorNodeId
                                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                                preserveBackStack = true,
                            )
                        }
                    }
                },
                resultActionLabel = when (item.task.resultKind) {
                    "AGENDA_TASK", "AGENDA_PLAN" -> "打开创建的事项"
                    "GITHUB" -> "查看 Issue"
                    else -> null
                },
                onResultClick = when (item.task.resultKind) {
                    "AGENDA_TASK" -> item.task.resultRef?.let { id ->
                        { navigator.navigate(Screen.AgendaTaskDetail(id)) }
                    }
                    "AGENDA_PLAN" -> item.task.resultRef?.let { id ->
                        { navigator.navigate(Screen.AgendaPlanDetail(id)) }
                    }
                    "GITHUB" -> item.task.resultRef?.let { url ->
                        { runCatching { uriHandler.openUri(url) } }
                    }
                    else -> null
                },
            )
        }
        snapshot.items.filterIsInstance<TodayItem.CurrentStatus>()
            .firstOrNull()
            ?.let { CompactStatusCard(snapshot = it.snapshot) }
        if (snapshot.agendaActionCount > 0) {
            AgendaActionBar(
                actionCount = snapshot.agendaActionCount,
                overdueCount = snapshot.agendaOverdueCount,
                onClick = onOpenAgenda,
            )
        }
        WorkEntryCard(
            waitingSessions = snapshot.waitingSessions,
            configured = snapshot.workConfigured,
        )
        if (snapshot.completedItems.isNotEmpty()) {
            TextButton(onClick = { showCompleted = !showCompleted }) {
                Text(if (showCompleted) "收起今天已完成" else "今天已完成 ${snapshot.completedItems.size} 项")
            }
            if (showCompleted) {
                snapshot.completedItems.forEach { item ->
                    AssistantTaskCard(task = item.task, onClick = {})
                }
            }
        }
    }
}

@Composable
internal fun AssistantTaskCard(
    task: AssistantTaskEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    resultActionLabel: String? = null,
    onResultClick: (() -> Unit)? = null,
) {
    val waiting = task.status == AssistantTaskStatus.WAITING_FOR_INPUT.name
    val failed = task.status == AssistantTaskStatus.FAILED_RETRYABLE.name
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = task.conversationId != null, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = when {
            waiting -> MaterialTheme.colorScheme.secondaryContainer
            failed -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = when (task.status) {
                    AssistantTaskStatus.WAITING_FOR_INPUT.name -> "等待你回答"
                    AssistantTaskStatus.FAILED_RETRYABLE.name -> "可以重试"
                    AssistantTaskStatus.RUNNING.name -> "正在处理"
                    AssistantTaskStatus.COMPLETED.name -> "已完成"
                    else -> "已停止"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    failed -> MaterialTheme.colorScheme.onErrorContainer
                    waiting -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            task.summary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (task.conversationId != null && task.status != AssistantTaskStatus.COMPLETED.name) {
                Text(
                    text = if (failed) "回到原聊天后可重新尝试" else "点按回到原聊天继续",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (resultActionLabel != null && onResultClick != null) {
                TextButton(onClick = onResultClick) {
                    Text(resultActionLabel)
                }
            }
        }
    }
}

@Composable
private fun WorkEntryCard(
    waitingSessions: List<PhoneWorkSession>,
    configured: Boolean,
) {
    val navigator = LocalNavController.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (waitingSessions.size == 1) {
                    navigator.navigate(Screen.PhoneWorkSession(waitingSessions.single().id))
                } else {
                    navigator.navigate(Screen.PhoneWorkHome)
                }
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Lucide.MessageCircleQuestion,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        waitingSessions.isNotEmpty() -> "有 ${waitingSessions.size} 个任务等你回复"
                        configured -> "打开 Work"
                        else -> "连接开发机"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                if (waitingSessions.size == 1) {
                    val session = waitingSessions.single()
                    Text(
                        text = session.title.ifBlank { session.repoName },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (waitingSessions.isEmpty()) {
                    Text(
                        text = if (configured) "继续开发机上的任务" else "配置 Work 后从手机发起开发任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun AgendaActionBar(
    actionCount: Int,
    overdueCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Lucide.ListChecks,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "$actionCount 项需处理",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (overdueCount > 0) {
                    Text(
                        text = "· 含逾期 $overdueCount",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompactStatusCard(
    snapshot: MyStatusSnapshot,
    coordinator: MyStatusCoordinator = koinInject(),
) {
    val navigator = LocalNavController.current
    val expired = snapshot.validUntilEpochMillis <= System.currentTimeMillis()
    LaunchedEffect(coordinator) { coordinator.onVisible() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "现在最值得注意",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = snapshot.summary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expired) {
                Text(
                    text = "状态可能已过时，请先更新。",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                snapshot.recommendation?.let { recommendation ->
                    Text(
                        text = recommendation.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        val runtimeContext = buildMyStatusRuntimeContext(snapshot)
                        if (runtimeContext == null) {
                            coordinator.refreshNow()
                        } else {
                            navigateToChatPage(
                                navigator = navigator,
                                runtimeContext = runtimeContext,
                                preserveBackStack = true,
                            )
                        }
                    },
                ) {
                    Text(if (expired) "更新状态" else "聊聊")
                }
            }
        }
    }
}
