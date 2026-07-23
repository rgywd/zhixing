package me.rerere.rikkahub.ui.pages.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.data.quota.ProviderQuotaOverview
import me.rerere.rikkahub.data.quota.ProviderQuotaStatus
import me.rerere.rikkahub.data.quota.QuotaRepository
import me.rerere.rikkahub.data.quota.QuotaRepositoryState
import me.rerere.rikkahub.data.quota.buildQuotaOverviews
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbe
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbeStage
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbeState
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
    val watchProbe: LenovoWatchProbe = koinInject()
    val quotaState by quotaRepository.state.collectAsStateWithLifecycle()
    val watchState by watchProbe.state.collectAsStateWithLifecycle()
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
                        subtitle = watchState.statusText,
                    )
                }
                item(key = "watch-probe") { WatchProbeCard(watchProbe, watchState) }
                item(key = "status-greeting") { StatusGreeting(watchState.statusText) }
                item(key = "steps") { StepsCard(watchState) }
                item(key = "status-grid") { StatusMetricGrid(watchState) }

                item(key = "agenda") { AgendaOverviewSection() }

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
private fun StatusGreeting(deviceStatus: String) {
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
                text = deviceStatus,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WatchProbeCard(
    watchProbe: LenovoWatchProbe,
    state: LenovoWatchProbeState,
) {
    val context = LocalContext.current
    val permissions = remember { requiredWatchPermissions() }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasWatchPermissions(context, permissions)) watchProbe.start()
    }
    LaunchedEffect(watchProbe) {
        if (
            state.remembered &&
            state.autoReconnectEnabled &&
            state.stage in setOf(LenovoWatchProbeStage.IDLE, LenovoWatchProbeStage.ERROR) &&
            hasWatchPermissions(context, permissions)
        ) {
            watchProbe.start()
        }
    }
    val active = state.stage !in setOf(LenovoWatchProbeStage.IDLE, LenovoWatchProbeStage.ERROR)

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
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Lenovo Watch Pro", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (state.remembered) {
                            "${state.address ?: "已知设备"} · 配对已保存，断连后自动恢复"
                        } else {
                            state.address ?: "首次连接后永久保存配对，不再依赖官方 App"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.stage in setOf(
                        LenovoWatchProbeStage.SCANNING,
                        LenovoWatchProbeStage.CONNECTING,
                        LenovoWatchProbeStage.DISCOVERING,
                        LenovoWatchProbeStage.ENABLING_NOTIFICATIONS,
                        LenovoWatchProbeStage.HANDSHAKING,
                        LenovoWatchProbeStage.SYNCING,
                    )
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }

            Text(
                text = state.error ?: state.statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.lastEvent?.let {
                Text(
                    text = if (state.stage == LenovoWatchProbeStage.SYNCING) {
                        "已处理 ${state.processedRecords} 条历史记录 · 完成后统一刷新"
                    } else {
                        it
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (active) {
                            watchProbe.disconnect()
                        } else if (hasWatchPermissions(context, permissions)) {
                            watchProbe.start()
                        } else {
                            permissionLauncher.launch(permissions)
                        }
                    },
                ) {
                    Text(
                        if (active) {
                            "暂时断开"
                        } else if (state.stage == LenovoWatchProbeStage.ERROR) {
                            "重试"
                        } else if (state.remembered) {
                            "重新连接"
                        } else {
                            "连接手表"
                        },
                    )
                }
                if (state.canSync) {
                    OutlinedButton(onClick = watchProbe::sync) {
                        Text("同步健康数据")
                    }
                }
            }
        }
    }
}

private fun requiredWatchPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun hasWatchPermissions(context: Context, permissions: Array<String>): Boolean = permissions.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

internal fun lifeOverviewGreeting(hour: Int): String = when (hour) {
    in 0..5 -> "夜深了"
    in 6..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    else -> "晚上好"
}

@Composable
private fun StepsCard(state: LenovoWatchProbeState) {
    val steps = state.health.steps
    val progress = ((steps ?: 0) / DAILY_STEP_GOAL.toFloat()).coerceIn(0f, 1f)
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
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 8.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = steps?.let { "${(progress * 100).roundToInt()}%" } ?: "--",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(if (steps == null) "未同步" else "今日", style = MaterialTheme.typography.labelSmall)
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
                Text(
                    text = steps?.let(::formatCount) ?: "--",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (steps == null) "连接手表并同步后显示" else "/ ${formatCount(DAILY_STEP_GOAL)} 步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusMetricGrid(state: LenovoWatchProbeState) {
    val health = state.health
    val sleepMinutes = health.totalSleepMinutes
        ?: listOfNotNull(health.shallowSleepMinutes, health.deepSleepMinutes)
            .takeIf { it.isNotEmpty() }
            ?.sum()
    val items = listOf(
        StatusPreviewItem(
            label = "睡眠",
            value = sleepMinutes?.let(::formatMinutes) ?: "--",
            detail = health.deepSleepMinutes?.let { "深睡 ${formatMinutes(it)}" } ?: "暂无睡眠数据",
            icon = HugeIcons.Clock02,
        ),
        StatusPreviewItem(
            label = "心率",
            value = health.heartRate?.let { "$it bpm" } ?: "--",
            detail = health.systolic?.let { systolic ->
                health.diastolic?.let { diastolic -> "血压 $systolic/$diastolic" }
            } ?: "最近一次有效测量",
            icon = HugeIcons.Favourite,
        ),
        StatusPreviewItem(
            label = "卡路里",
            value = health.calories?.let { "$it 千卡" } ?: "--",
            detail = "今日累计",
            icon = HugeIcons.Zap,
        ),
        StatusPreviewItem(
            label = "运动锻炼",
            value = health.exerciseCount?.let { "$it 次" } ?: "--",
            detail = health.exerciseSeconds?.let { "共 ${formatMinutes(it / 60)}" } ?: "暂无运动数据",
            icon = HugeIcons.Rocket01,
        ),
        StatusPreviewItem(
            label = "血氧",
            value = health.bloodOxygen?.let { "$it%" } ?: "--",
            detail = "最近一次有效测量",
            icon = HugeIcons.Favourite,
        ),
        StatusPreviewItem(
            label = "体温",
            value = health.temperatureCelsius?.let { String.format("%.1f℃", it) } ?: "--",
            detail = health.immunity?.let { "免疫力 $it" } ?: "最近一次有效测量",
            icon = HugeIcons.Time02,
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { rowItems ->
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

private fun formatCount(value: Int): String = String.format("%,d", value)

private fun formatMinutes(value: Int): String = when {
    value <= 0 -> "0 分钟"
    value < 60 -> "$value 分钟"
    value % 60 == 0 -> "${value / 60} 小时"
    else -> "${value / 60}小时${value % 60}分"
}

private val QUOTA_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private const val DAILY_STEP_GOAL = 10_000

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
            pass = PointerEventPass.Initial,
        )
        var totalX = 0f
        var totalY = 0f

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val delta = change.position - change.previousPosition
            totalX += delta.x
            totalY += delta.y
            if (isAgendaSwipeTriggered(direction, totalX, totalY, threshold)) {
                change.consume()
                onSwipe()
                break
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
