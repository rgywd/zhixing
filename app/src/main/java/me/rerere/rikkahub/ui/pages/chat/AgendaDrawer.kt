package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import me.rerere.rikkahub.data.quota.QuotaAccountOverview
import me.rerere.rikkahub.data.quota.LOW_QUOTA_PERCENT
import me.rerere.rikkahub.data.quota.QuotaRepository
import me.rerere.rikkahub.data.quota.QuotaRepositoryState
import me.rerere.rikkahub.data.quota.QuotaState
import me.rerere.rikkahub.data.quota.QuotaWindowOverview
import me.rerere.rikkahub.data.quota.buildQuotaOverviews
import me.rerere.rikkahub.data.quota.orderQuotaChannels
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbe
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbeState
import me.rerere.rikkahub.data.today.TodayOverviewProvider
import me.rerere.rikkahub.data.today.TodayItem
import me.rerere.rikkahub.data.work.PhoneWorkSession
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalDrawerGestureExclusion
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.work.WorkStatusChip
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.uuid.Uuid
import me.rerere.rikkahub.utils.navigateToChatPage
import kotlin.math.abs
import kotlin.math.roundToInt

@Stable
internal class AgendaDrawerState(
    initialValue: DrawerValue,
) {
    private var offsetPx by mutableFloatStateOf(0f)
    private var drawerWidthPx by mutableFloatStateOf(Float.NaN)
    private var animationJob: Job? = null

    var currentValue by mutableStateOf(initialValue)
        private set
    var targetValue by mutableStateOf(initialValue)
        private set

    val isOpen: Boolean
        get() = currentValue == DrawerValue.Open

    val isVisible: Boolean
        get() = targetValue == DrawerValue.Open ||
            currentValue == DrawerValue.Open ||
            (drawerWidthPx.isFinite() && offsetPx < drawerWidthPx)

    val currentOffset: Float
        get() = offsetPx

    val openFraction: Float
        get() = if (drawerWidthPx.isFinite() && drawerWidthPx > 0f) {
            (1f - offsetPx / drawerWidthPx).coerceIn(0f, 1f)
        } else {
            0f
        }

    val hasWidth: Boolean
        get() = drawerWidthPx.isFinite() && drawerWidthPx > 0f

    fun updateWidth(newWidthPx: Float) {
        if (!newWidthPx.isFinite() || newWidthPx <= 0f || newWidthPx == drawerWidthPx) return
        animationJob?.cancel()
        val previousWidthPx = drawerWidthPx
        val nextOffset = if (previousWidthPx.isFinite() && previousWidthPx > 0f) {
            (offsetPx / previousWidthPx * newWidthPx).coerceIn(0f, newWidthPx)
        } else if (targetValue == DrawerValue.Open) {
            0f
        } else {
            newWidthPx
        }
        drawerWidthPx = newWidthPx
        offsetPx = nextOffset
        if (nextOffset == 0f) currentValue = DrawerValue.Open
        if (nextOffset == newWidthPx) currentValue = DrawerValue.Closed
    }

    fun open(scope: CoroutineScope) {
        animateTo(scope, DrawerValue.Open)
    }

    fun close(scope: CoroutineScope) {
        animateTo(scope, DrawerValue.Closed)
    }

    fun beginDrag() {
        animationJob?.cancel()
        targetValue = currentValue
    }

    fun dragBy(deltaX: Float) {
        if (!hasWidth) return
        offsetPx = (offsetPx + deltaX).coerceIn(0f, drawerWidthPx)
        targetValue = if (openFraction >= AGENDA_DRAWER_POSITIONAL_THRESHOLD) {
            DrawerValue.Open
        } else {
            DrawerValue.Closed
        }
    }

    fun settle(
        scope: CoroutineScope,
        velocityX: Float,
        velocityThresholdPx: Float,
    ) {
        val target = if (
            shouldOpenAgendaDrawer(
                openFraction = openFraction,
                velocityX = velocityX,
                velocityThresholdPx = velocityThresholdPx,
            )
        ) {
            DrawerValue.Open
        } else {
            DrawerValue.Closed
        }
        animateTo(scope, target, initialVelocity = velocityX)
    }

    private fun animateTo(
        scope: CoroutineScope,
        target: DrawerValue,
        initialVelocity: Float = 0f,
    ) {
        targetValue = target
        if (!hasWidth) return
        animationJob?.cancel()
        animationJob = scope.launch {
            animate(
                initialValue = offsetPx,
                targetValue = if (target == DrawerValue.Open) 0f else drawerWidthPx,
                initialVelocity = initialVelocity,
                animationSpec = tween(durationMillis = AGENDA_DRAWER_ANIMATION_DURATION_MS),
            ) { value, _ ->
                offsetPx = value.coerceIn(0f, drawerWidthPx)
            }
            currentValue = target
        }
    }

    companion object {
        fun Saver(): Saver<AgendaDrawerState, DrawerValue> = Saver(
            save = { state ->
                if (state.openFraction >= AGENDA_DRAWER_POSITIONAL_THRESHOLD) {
                    DrawerValue.Open
                } else {
                    DrawerValue.Closed
                }
            },
            restore = ::AgendaDrawerState,
        )
    }
}

@Composable
internal fun rememberAgendaDrawerState(
    initialValue: DrawerValue = DrawerValue.Closed,
): AgendaDrawerState = rememberSaveable(saver = AgendaDrawerState.Saver()) {
    AgendaDrawerState(initialValue)
}

@Composable
internal fun AgendaDrawerHost(
    drawerState: AgendaDrawerState,
    contentDrawerState: DrawerState? = null,
    openingGestureEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val drawerGestureExclusion = remember { mutableStateOf(false) }
    val velocityThresholdPx = with(density) { AGENDA_DRAWER_VELOCITY_THRESHOLD.toPx() }
    BackHandler(enabled = drawerState.isVisible) {
        drawerState.close(scope)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .agendaDrawerDragGesture(
                drawerState = drawerState,
                animationScope = scope,
                velocityThresholdPx = velocityThresholdPx,
                openingGestureEnabled = openingGestureEnabled,
                gestureBlocked = {
                    contentDrawerState?.let {
                        it.currentValue == DrawerValue.Open ||
                            it.targetValue == DrawerValue.Open
                    } == true || drawerGestureExclusion.value
                },
            ),
    ) {
        val drawerWidth = minOf(maxWidth * 0.88f, 360.dp)
        val drawerWidthPx = with(density) { drawerWidth.toPx() }
        SideEffect {
            drawerState.updateWidth(drawerWidthPx)
        }
        val drawerActive by remember(drawerState) { derivedStateOf { drawerState.isVisible } }
        val interactionSource = remember { MutableInteractionSource() }
        val scrimColor = DrawerDefaults.scrimColor

        CompositionLocalProvider(
            LocalDrawerGestureExclusion provides drawerGestureExclusion,
        ) {
            content()
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (drawerActive) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "关闭生活概览",
                            onClick = { drawerState.close(scope) },
                        )
                    } else {
                        Modifier
                    }
                ),
        ) {
            drawRect(scrimColor, alpha = drawerState.openFraction)
        }

        ModalDrawerSheet(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset {
                    IntOffset(
                        x = if (drawerState.hasWidth) {
                            drawerState.currentOffset.roundToInt()
                        } else {
                            drawerWidthPx.roundToInt()
                        },
                        y = 0,
                    )
                }
                .width(drawerWidth),
            drawerShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
        ) {
            if (drawerActive) {
                LifeOverviewDrawerContent(
                    onClose = { drawerState.close(scope) },
                )
            } else {
                Box(modifier = Modifier.fillMaxHeight())
            }
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

    val todayProvider: TodayOverviewProvider = koinInject()
    val todaySnapshot by todayProvider.state.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    LaunchedEffect(todayProvider) { todayProvider.onVisible() }

    Column(modifier = modifier.fillMaxHeight()) {
        LifeOverviewHeader(onClose = onClose)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "status-title") {
                OverviewSectionTitle(
                    title = "我的状态",
                    subtitle = "理解身体、环境与安排中最值得注意的部分",
                )
            }
            item(key = "my-status") { MyStatusCard() }

            todaySnapshot.items.filterIsInstance<TodayItem.AssistantTask>().forEach { taskItem ->
                item(key = taskItem.stableId) {
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
                    )
                }
            }

            if (todaySnapshot.waitingSessions.isNotEmpty()) {
                item(key = "work-waiting") {
                    WorkWaitingSection(waitingSessions = todaySnapshot.waitingSessions)
                }
            }

            item(key = "agenda") { AgendaOverviewSection() }

            item(key = "quota-title") {
                OverviewSectionTitle(
                    title = "套餐余量",
                    subtitle = quotaSectionSubtitle(quotaState),
                )
            }
            item(key = "quota-deck") {
                QuotaChannelDeck(quotaItems)
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
private fun WorkWaitingSection(waitingSessions: List<PhoneWorkSession>) {
    val navigator = LocalNavController.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OverviewSectionTitle(
            title = "Work",
            subtitle = "${waitingSessions.size} 个会话等你回答",
        )
        waitingSessions.take(2).forEach { session ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navigator.navigate(Screen.PhoneWorkSession(session.id)) },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = session.title.ifBlank { session.repoName },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    WorkStatusChip(status = session.status)
                }
            }
        }
        if (waitingSessions.size > 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "查看全部",
                    modifier = Modifier
                        .clickable { navigator.navigate(Screen.PhoneWorkHome) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StatusGreeting() {
    val now = remember { Calendar.getInstance() }
    val hour = now.get(Calendar.HOUR_OF_DAY)
    val weekday = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        .getOrElse(now.get(Calendar.DAY_OF_WEEK) - 1) { "" }
    val monthDay = "${now.get(Calendar.MONTH) + 1}月${now.get(Calendar.DAY_OF_MONTH)}日"
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
                text = "${lifeOverviewGreeting(hour)} · $monthDay $weekday",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
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
                    text = if (steps == null) "暂无健康数据" else "/ ${formatCount(DAILY_STEP_GOAL)} 步",
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
            value = health.temperatureCelsius?.let { String.format(Locale.CHINA, "%.1f℃", it) } ?: "--",
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
private fun QuotaChannelDeck(items: List<ProviderQuotaOverview>) {
    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(items.map { it.provider }) {
        if (items.none { it.provider == selectedProvider }) {
            selectedProvider = items.firstOrNull()?.provider
        }
    }
    val orderedItems = remember(items, selectedProvider) {
        orderQuotaChannels(items, selectedProvider)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy((-4).dp),
    ) {
        orderedItems.forEach { item ->
            QuotaChannelCard(
                item = item,
                expanded = item.provider == selectedProvider,
                onClick = { selectedProvider = item.provider },
            )
        }
    }
}

@Composable
private fun QuotaChannelCard(
    item: ProviderQuotaOverview,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val statusColor = when (item.status) {
        ProviderQuotaStatus.LOW, ProviderQuotaStatus.ERROR -> MaterialTheme.colorScheme.error
        ProviderQuotaStatus.PARTIAL, ProviderQuotaStatus.STALE, ProviderQuotaStatus.UNAVAILABLE ->
            MaterialTheme.colorScheme.tertiary
        ProviderQuotaStatus.OK -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(220))
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) null else "展开 ${item.displayName} 套餐余量",
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = if (expanded) 2.dp else 1.dp,
        shadowElevation = if (expanded) 2.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuotaChannelHeader(item, statusColor)
            if (expanded) {
                QuotaChannelDetails(item)
            }
        }
    }
}

@Composable
private fun QuotaChannelHeader(
    item: ProviderQuotaOverview,
    statusColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(HugeIcons.MoneyBag02, contentDescription = null, modifier = Modifier.size(17.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = quotaChannelSummary(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
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
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun QuotaChannelDetails(item: ProviderQuotaOverview) {
    val showAccountLabels = item.accountCount > 1
    if (item.accounts.isEmpty()) {
        Text(
            text = "暂无已接入凭据",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item.accounts.forEach { account ->
            if (showAccountLabels) {
                QuotaAccountHeader(account)
            }
            when {
                account.state != QuotaState.OK -> {
                    Text(
                        text = quotaAccountStateText(account.state),
                        style = MaterialTheme.typography.labelSmall,
                        color = quotaAccountStateColor(account.state),
                        modifier = Modifier.padding(start = if (showAccountLabels) 8.dp else 0.dp),
                    )
                }
                account.windows.isEmpty() -> {
                    Text(
                        text = "暂无可计算额度",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = if (showAccountLabels) 8.dp else 0.dp),
                    )
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        account.windows.forEach { window ->
                            QuotaWindowRow(window)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotaAccountHeader(account: QuotaAccountOverview) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = account.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        account.plan?.takeIf { it.isNotBlank() }?.let { plan ->
            Text(
                text = plan,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun QuotaWindowRow(window: QuotaWindowOverview) {
    val remaining = window.remainingPercent
    val color = if (remaining != null && remaining <= LOW_QUOTA_PERCENT) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = listOfNotNull(window.groupLabel, window.label).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = remaining?.let(::formatPercent) ?: "余量未知",
                style = MaterialTheme.typography.labelMedium,
                color = if (remaining == null) MaterialTheme.colorScheme.onSurfaceVariant else color,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        remaining?.let {
            LinearProgressIndicator(
                progress = { (it / 100.0).toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
        window.resetAt?.let { resetAt ->
            Text(
                text = "${formatQuotaTime(resetAt)} 重置",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun quotaAccountStateColor(state: QuotaState) = when (state) {
    QuotaState.ERROR -> MaterialTheme.colorScheme.error
    QuotaState.UNAVAILABLE -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun quotaAccountStateText(state: QuotaState): String = when (state) {
    QuotaState.OK -> "可用"
    QuotaState.DISABLED -> "已禁用"
    QuotaState.UNAVAILABLE -> "暂不可用，余量未知"
    QuotaState.UNSUPPORTED -> "暂不支持额度查询"
    QuotaState.ERROR -> "采集失败，余量未知"
}

private fun quotaChannelSummary(item: ProviderQuotaOverview): String = when {
    item.accountCount == 0 -> "暂无已接入凭据"
    item.windowCount > 0 -> "${item.usableAccountCount}/${item.accountCount} 个账户可用"
    item.status == ProviderQuotaStatus.DISABLED -> "${item.accountCount} 个账户 · 已禁用"
    item.status == ProviderQuotaStatus.UNAVAILABLE -> "${item.accountCount} 个账户 · 暂不可用"
    item.status == ProviderQuotaStatus.UNSUPPORTED -> "${item.accountCount} 个账户 · 不支持查询"
    item.status == ProviderQuotaStatus.ERROR -> "${item.accountCount} 个账户 · 采集失败"
    else -> "${item.accountCount} 个账户 · 余量未知"
}

private fun quotaBadge(item: ProviderQuotaOverview): String = when {
    item.windowCount > 1 && item.lowWindowCount > 0 -> "${item.windowCount} 项 · ${item.lowWindowCount} 项偏低"
    item.windowCount > 1 -> "${item.windowCount} 项额度"
    item.windowCount == 1 && item.accountCount == 1 -> item.remainingPercent?.let(::formatPercent) ?: "余量未知"
    item.windowCount == 1 -> "1 项额度"
    else -> when (item.status) {
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

private fun formatPercent(value: Double): String = if (value % 1.0 == 0.0) {
    "${value.roundToInt()}%"
} else {
    String.format(Locale.CHINA, "%.1f%%", value)
}

private fun formatQuotaTime(value: String): String = runCatching {
    QUOTA_TIME_FORMATTER.format(Instant.parse(value).atZone(ZoneId.systemDefault()))
}.getOrDefault("未知时间")

private fun healthDataSubtitle(value: Instant?): String = value?.let {
    "健康数据更新于 ${STATUS_TIME_FORMATTER.format(it.atZone(ZoneId.systemDefault()))}"
} ?: "健康数据尚未更新"

private data class StatusPreviewItem(
    val label: String,
    val value: String,
    val detail: String,
    val icon: ImageVector,
    val progress: Float? = null,
)

private fun formatCount(value: Int): String = String.format(Locale.CHINA, "%,d", value)

private fun formatMinutes(value: Int): String = when {
    value <= 0 -> "0 分钟"
    value < 60 -> "$value 分钟"
    value % 60 == 0 -> "${value / 60} 小时"
    else -> "${value / 60}小时${value % 60}分"
}

private val QUOTA_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val STATUS_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private const val DAILY_STEP_GOAL = 10_000
private const val AGENDA_DRAWER_POSITIONAL_THRESHOLD = 0.5f
private const val AGENDA_DRAWER_ANIMATION_DURATION_MS = 256
private val AGENDA_DRAWER_VELOCITY_THRESHOLD = 400.dp

internal fun shouldOpenAgendaDrawer(
    openFraction: Float,
    velocityX: Float,
    velocityThresholdPx: Float,
): Boolean = when {
    velocityX < -velocityThresholdPx -> true
    velocityX > velocityThresholdPx -> false
    else -> openFraction >= AGENDA_DRAWER_POSITIONAL_THRESHOLD
}

internal enum class AgendaDrawerDragDecision {
    WAIT,
    START,
    IGNORE,
}

internal fun agendaDrawerDragDecision(
    drawerVisible: Boolean,
    gestureBlocked: Boolean,
    totalX: Float,
    totalY: Float,
    touchSlop: Float,
    openingGestureEnabled: Boolean = true,
): AgendaDrawerDragDecision {
    val horizontalGesture = abs(totalX) > touchSlop && abs(totalX) > abs(totalY)
    val verticalGesture = abs(totalY) > touchSlop && abs(totalY) >= abs(totalX)
    return when {
        !drawerVisible && (!openingGestureEnabled || gestureBlocked) -> AgendaDrawerDragDecision.IGNORE
        verticalGesture -> AgendaDrawerDragDecision.IGNORE
        !horizontalGesture -> AgendaDrawerDragDecision.WAIT
        drawerVisible || totalX < 0f -> AgendaDrawerDragDecision.START
        else -> AgendaDrawerDragDecision.IGNORE
    }
}

private fun Modifier.agendaDrawerDragGesture(
    drawerState: AgendaDrawerState,
    animationScope: CoroutineScope,
    velocityThresholdPx: Float,
    openingGestureEnabled: Boolean,
    gestureBlocked: () -> Boolean,
): Modifier = pointerInput(
    drawerState,
    animationScope,
    velocityThresholdPx,
    openingGestureEnabled,
) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        val velocityTracker = VelocityTracker().apply {
            addPosition(down.uptimeMillis, down.position)
        }
        var totalX = 0f
        var totalY = 0f
        var dragging = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            val delta = change.positionChange()

            if (!change.pressed) {
                if (dragging) {
                    drawerState.settle(
                        scope = animationScope,
                        velocityX = velocityTracker.calculateVelocity().x,
                        velocityThresholdPx = velocityThresholdPx,
                    )
                }
                break
            }

            if (dragging) {
                drawerState.dragBy(delta.x)
                change.consume()
                continue
            }

            totalX += delta.x
            totalY += delta.y
            when (
                agendaDrawerDragDecision(
                    drawerVisible = drawerState.isVisible,
                    gestureBlocked = gestureBlocked(),
                    totalX = totalX,
                    totalY = totalY,
                    touchSlop = viewConfiguration.touchSlop,
                    openingGestureEnabled = openingGestureEnabled,
                )
            ) {
                AgendaDrawerDragDecision.WAIT -> continue
                AgendaDrawerDragDecision.IGNORE -> break
                AgendaDrawerDragDecision.START -> {
                    dragging = true
                    drawerState.beginDrag()
                    drawerState.dragBy(totalX)
                    change.consume()
                }
            }
        }
    }
}
