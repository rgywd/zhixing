package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlarmClock
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Task01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.data.quota.ProviderQuotaOverview
import me.rerere.rikkahub.data.quota.ProviderQuotaStatus
import me.rerere.rikkahub.data.quota.QuotaRepository
import me.rerere.rikkahub.data.quota.QuotaRepositoryState
import me.rerere.rikkahub.data.quota.buildQuotaOverviews
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AgendaDrawerHost(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = visible, onBack = onDismissRequest)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        content()

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(160)),
        ) {
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "关闭生活概览",
                        onClick = onDismissRequest,
                    )
            )
        }

        val drawerWidth = minOf(maxWidth * 0.88f, 360.dp)
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(260),
            ) + fadeIn(animationSpec = tween(180)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(200),
            ) + fadeOut(animationSpec = tween(140)),
        ) {
            LifeOverviewDrawerContent(
                onClose = onDismissRequest,
                modifier = Modifier.width(drawerWidth),
            )
        }
    }
}

@Composable
private fun LifeOverviewDrawerContent(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val quotaRepository: QuotaRepository = koinInject()
    val quotaState by quotaRepository.state.collectAsStateWithLifecycle()
    val quotaItems = remember(quotaState.envelope) { buildQuotaOverviews(quotaState.envelope) }
    LaunchedEffect(quotaRepository) { quotaRepository.refresh() }

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .agendaSwipeGesture(
                direction = AgendaSwipeDirection.CLOSE,
                onSwipe = onClose,
            ),
        shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            LifeOverviewHeader(onClose = onClose)
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "status-title") {
                    OverviewSectionTitle(
                        title = "我的状态",
                        subtitle = "预览数据 · 等待手表或健康服务接入",
                    )
                }
                item(key = "status-greeting") { StatusGreeting() }
                item(key = "steps") { StepsCard() }
                item(key = "status-grid") { StatusMetricGrid() }

                item(key = "agenda-title") {
                    OverviewSectionTitle(
                        title = "我的事项",
                        subtitle = "今天 3 项待处理，其中 1 项已逾期",
                    )
                }
                items(agendaOverviewItems, key = { it.id }) { item ->
                    AgendaOverviewCard(item)
                }

                item(key = "quota-title") {
                    OverviewSectionTitle(
                        title = "套餐余量",
                        subtitle = quotaSectionSubtitle(quotaState),
                    )
                }
                items(quotaItems, key = { it.provider }) { item ->
                    QuotaOverviewCard(item)
                }
            }
        }
    }
}

@Composable
private fun LifeOverviewHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = HugeIcons.Sun01,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "现在",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "你的生活概览",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClose) {
            Icon(HugeIcons.Cancel01, contentDescription = "关闭生活概览")
        }
    }
}

@Composable
private fun OverviewSectionTitle(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusGreeting() {
    val now = remember { Calendar.getInstance() }
    val hour = now.get(Calendar.HOUR_OF_DAY)
    val weekday = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        .getOrElse(now.get(Calendar.DAY_OF_WEEK) - 1) { "" }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(HugeIcons.Sun01, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Column {
            Text(
                text = "${lifeOverviewGreeting(hour)} · ${now.get(Calendar.MONTH) + 1}月${now.get(Calendar.DAY_OF_MONTH)}日 $weekday",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "设备尚未连接",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun lifeOverviewGreeting(hour: Int): String = when (hour) {
    in 0..5 -> "夜深了"
    in 6..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    else -> "晚上好"
}

@Composable
private fun StepsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier.size(82.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { 0.83f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 8.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("83%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("预览", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(HugeIcons.ChartColumn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("步数", style = MaterialTheme.typography.labelMedium)
                }
                Text("8,342", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "/ 10,000 步 · 较昨日 +17%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusMetricGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        statusPreviewItems.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { item ->
                    StatusMetricCard(item = item, modifier = Modifier.weight(1f))
                }
                if (rowItems.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatusMetricCard(
    item: StatusPreviewItem,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(item.icon, contentDescription = null, modifier = Modifier.size(15.dp))
                    }
                }
                Text(item.label, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                text = item.value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

@Composable
private fun AgendaOverviewCard(item: AgendaOverviewItem) {
    val isOverdue = item.kind == AgendaOverviewKind.OVERDUE
    val containerColor = when (item.kind) {
        AgendaOverviewKind.OVERDUE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.62f)
        AgendaOverviewKind.REMINDER -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.62f)
        AgendaOverviewKind.EVENT -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    }
    val icon = when (item.kind) {
        AgendaOverviewKind.OVERDUE -> HugeIcons.Task01
        AgendaOverviewKind.REMINDER -> HugeIcons.AlarmClock
        AgendaOverviewKind.EVENT -> HugeIcons.Calendar03
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun QuotaOverviewCard(item: ProviderQuotaOverview) {
    val statusColor = when (item.status) {
        ProviderQuotaStatus.LOW, ProviderQuotaStatus.ERROR -> MaterialTheme.colorScheme.error
        ProviderQuotaStatus.PARTIAL, ProviderQuotaStatus.STALE, ProviderQuotaStatus.UNAVAILABLE ->
            MaterialTheme.colorScheme.tertiary
        ProviderQuotaStatus.OK -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(HugeIcons.MoneyBag02, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        text = quotaDetail(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = quotaBadge(item),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                }
            }
            item.remainingPercent?.let { remaining ->
                LinearProgressIndicator(
                    progress = { (remaining / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

private fun quotaSectionSubtitle(state: QuotaRepositoryState): String = when {
    state.envelope == null && state.refreshing -> "正在同步套餐余量"
    state.envelope == null && state.errorMessage != null -> "同步失败 · ${state.errorMessage}"
    state.errorMessage != null -> "显示上次结果 · 本次同步失败"
    state.envelope?.proxyStale == true || state.fromDeviceCache -> "显示上次成功结果 · 数据可能已过期"
    state.refreshing -> "正在后台更新 · 当前显示上次结果"
    state.envelope != null -> "更新于 ${formatQuotaTime(state.envelope.generatedAt)}"
    else -> "统一查看模型服务的可用额度"
}

private fun quotaDetail(item: ProviderQuotaOverview): String = when {
    item.accountCount == 0 -> "暂无已接入凭据"
    item.remainingPercent != null -> buildString {
        append("${item.usableAccountCount}/${item.accountCount} 个账户可用")
        item.windowLabel?.let { append(" · $it") }
        item.resetAt?.let { append(" · ${formatQuotaTime(it)} 重置") }
    }
    item.status == ProviderQuotaStatus.DISABLED -> "${item.accountCount} 个账户 · 已禁用"
    item.status == ProviderQuotaStatus.UNAVAILABLE -> "${item.accountCount} 个账户 · 暂不可用，余量未知"
    item.status == ProviderQuotaStatus.UNSUPPORTED -> "${item.accountCount} 个账户 · 暂不支持额度查询"
    item.status == ProviderQuotaStatus.ERROR -> "${item.accountCount} 个账户 · 采集失败，余量未知"
    else -> "${item.accountCount} 个账户 · 暂无可计算额度"
}

private fun quotaBadge(item: ProviderQuotaOverview): String = item.remainingPercent?.let(::formatPercent)
    ?: when (item.status) {
        ProviderQuotaStatus.MISSING -> "未接入"
        ProviderQuotaStatus.DISABLED -> "已禁用"
        ProviderQuotaStatus.UNAVAILABLE -> "暂不可用"
        ProviderQuotaStatus.UNSUPPORTED -> "不支持"
        ProviderQuotaStatus.ERROR -> "采集失败"
        ProviderQuotaStatus.PARTIAL -> "部分异常"
        ProviderQuotaStatus.STALE -> "旧数据"
        ProviderQuotaStatus.LOW -> "余量偏低"
        ProviderQuotaStatus.OK -> "未知"
    }

private fun formatPercent(value: Double): String = if (value % 1.0 == 0.0) {
    "${value.roundToInt()}%"
} else {
    String.format("%.1f%%", value)
}

private fun formatQuotaTime(value: String): String = runCatching {
    QUOTA_TIME_FORMATTER.format(Instant.parse(value).atZone(ZoneId.systemDefault()))
}.getOrDefault("未知时间")

private data class StatusPreviewItem(
    val label: String,
    val value: String,
    val detail: String,
    val icon: ImageVector,
    val progress: Float? = null,
)

private val statusPreviewItems = listOf(
    StatusPreviewItem("睡眠", "7h24m", "深睡 1h58m", HugeIcons.Clock02, 0.82f),
    StatusPreviewItem("心率", "58~76", "bpm · 实时", HugeIcons.Favourite),
    StatusPreviewItem("卡路里", "486 千卡", "较昨日 13%", HugeIcons.Zap),
    StatusPreviewItem("运动锻炼", "2 次", "共 58 分钟", HugeIcons.Rocket01),
    StatusPreviewItem("血氧", "97%", "与昨日持平", HugeIcons.Favourite),
    StatusPreviewItem("活动小时", "11/12h", "还差 1 小时", HugeIcons.Time02, 0.92f),
)

private enum class AgendaOverviewKind {
    OVERDUE,
    REMINDER,
    EVENT,
}

private data class AgendaOverviewItem(
    val id: String,
    val title: String,
    val detail: String,
    val label: String,
    val kind: AgendaOverviewKind,
)

private val agendaOverviewItems = listOf(
    AgendaOverviewItem("overdue-report", "提交季度总结", "昨天 18:00 截止", "已逾期", AgendaOverviewKind.OVERDUE),
    AgendaOverviewItem("review-meeting", "产品评审会议", "14:30–15:30 · 第三会议室", "日历", AgendaOverviewKind.EVENT),
    AgendaOverviewItem("call-family", "给父母打电话", "今天 20:00 提醒", "待提醒", AgendaOverviewKind.REMINDER),
)

private val QUOTA_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")

internal enum class AgendaSwipeDirection {
    OPEN,
    CLOSE,
}

internal fun Modifier.agendaSwipeGesture(
    direction: AgendaSwipeDirection,
    onSwipe: () -> Unit,
): Modifier = pointerInput(direction, onSwipe) {
    val threshold = 64.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Main,
        )
        var totalX = 0f
        var totalY = 0f
        var blockedByChild = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.isConsumed) {
                blockedByChild = true
            } else if (!blockedByChild) {
                val delta = change.positionChange()
                totalX += delta.x
                totalY += delta.y
                if (isAgendaSwipeTriggered(direction, totalX, totalY, threshold)) {
                    onSwipe()
                    break
                }
            }
            if (!change.pressed) break
        }
    }
}

internal fun isAgendaSwipeTriggered(
    direction: AgendaSwipeDirection,
    totalX: Float,
    totalY: Float,
    threshold: Float,
): Boolean {
    val directionMatches = when (direction) {
        AgendaSwipeDirection.OPEN -> totalX < 0f
        AgendaSwipeDirection.CLOSE -> totalX > 0f
    }
    return directionMatches && abs(totalX) >= threshold && abs(totalX) > abs(totalY) * 1.25f
}
