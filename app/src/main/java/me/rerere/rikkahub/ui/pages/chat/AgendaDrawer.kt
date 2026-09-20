package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.rikkahub.data.today.TodayOverviewProvider
import me.rerere.rikkahub.data.today.TodayItem
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbe
import me.rerere.rikkahub.data.repository.HealthMetricRepository
import me.rerere.rikkahub.data.work.PhoneWorkSession
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.LocalDrawerGestureExclusion
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.stats.buildHealthStatsUiState
import me.rerere.rikkahub.ui.pages.work.WorkStatusChip
import org.koin.compose.koinInject
import java.time.ZonedDateTime
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
    val todayProvider: TodayOverviewProvider = koinInject()
    val todaySnapshot by todayProvider.state.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    LaunchedEffect(todayProvider) { todayProvider.onVisible() }

    // 健康速览：Room 结构化记录 + 联想手表快照，复用统计页的合并逻辑
    val healthMetricRepository: HealthMetricRepository = koinInject()
    val watchProbe: LenovoWatchProbe = koinInject()
    val healthRecords by healthMetricRepository.observeRecords()
        .collectAsStateWithLifecycle(null)
    val watchState by watchProbe.state.collectAsStateWithLifecycle()
    val healthStats = remember(healthRecords, watchState) {
        healthRecords?.let { records ->
            buildHealthStatsUiState(records = records, watchState = watchState)
        }
    }

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

            healthStats?.takeIf { it.hasAnyData }?.let { stats ->
                item(key = "health-glance") { HealthGlanceCard(stats = stats) }
            }

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
                        onDismiss = { todayProvider.dismissFailedTask(taskItem.task.id) },
                    )
                }
            }

            item(key = "agenda") { AgendaOverviewSection() }
        }
    }
}

@Composable
private fun LifeOverviewHeader(onClose: () -> Unit) {
    val now = remember { ZonedDateTime.now() }
    val weekday = HEADER_WEEKDAYS.getOrElse(now.dayOfWeek.value - 1) { "" }
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
                text = "${lifeOverviewGreeting(now.hour)} · ${now.monthValue}月${now.dayOfMonth}日 $weekday",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

private val HEADER_WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

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

internal fun lifeOverviewGreeting(hour: Int): String = when (hour) {
    in 0..5 -> "夜深了"
    in 6..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    else -> "晚上好"
}

private const val AGENDA_DRAWER_POSITIONAL_THRESHOLD = 0.5f
private const val AGENDA_DRAWER_ANIMATION_DURATION_MS = 256
private val AGENDA_DRAWER_VELOCITY_THRESHOLD = 400.dp
private val AGENDA_DRAWER_EDGE_ZONE = 32.dp

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
    startedInEdgeZone: Boolean = true,
): AgendaDrawerDragDecision {
    val horizontalGesture = abs(totalX) > touchSlop && abs(totalX) > abs(totalY)
    val verticalGesture = abs(totalY) > touchSlop && abs(totalY) >= abs(totalX)
    return when {
        !drawerVisible && (!openingGestureEnabled || gestureBlocked) -> AgendaDrawerDragDecision.IGNORE
        verticalGesture -> AgendaDrawerDragDecision.IGNORE
        !horizontalGesture -> AgendaDrawerDragDecision.WAIT
        drawerVisible -> AgendaDrawerDragDecision.START
        totalX < 0f && startedInEdgeZone -> AgendaDrawerDragDecision.START
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
        // 关闭态只允许从屏幕右缘热区起手左滑打开；抽屉可见时全屏拖拽用于关闭
        val startedInEdgeZone = down.position.x >= size.width - AGENDA_DRAWER_EDGE_ZONE.toPx()
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
                    startedInEdgeZone = startedInEdgeZone,
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
