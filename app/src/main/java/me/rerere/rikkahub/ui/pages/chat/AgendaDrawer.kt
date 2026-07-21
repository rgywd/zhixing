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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlarmClock
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Task01

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
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "关闭事项边栏",
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
            AgendaDrawerContent(
                onClose = onDismissRequest,
                modifier = Modifier.width(drawerWidth),
            )
        }
    }
}

@Composable
private fun AgendaDrawerContent(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedFilter by remember { mutableStateOf(AgendaFilter.TODAY) }
    var completedIds by remember { mutableStateOf(setOf("completed-plan")) }
    val items = remember { agendaPreviewItems }

    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            AgendaHeader(onClose = onClose)
            AgendaSummary()
            AgendaFilters(
                selected = selectedFilter,
                onSelected = { selectedFilter = it },
            )

            val visibleItems = items.filter { item ->
                val section = item.section(completedIds)
                when (selectedFilter) {
                    AgendaFilter.TODAY -> section == AgendaSection.OVERDUE || section == AgendaSection.TODAY
                    AgendaFilter.ALL -> true
                    AgendaFilter.TASKS -> item.kind != AgendaKind.EVENT && section != AgendaSection.COMPLETED
                    AgendaFilter.CALENDAR -> item.kind == AgendaKind.EVENT
                    AgendaFilter.COMPLETED -> section == AgendaSection.COMPLETED
                }
            }

            AgendaItemList(
                items = visibleItems,
                completedIds = completedIds,
                onToggleCompleted = { id ->
                    completedIds = if (id in completedIds) completedIds - id else completedIds + id
                },
            )
        }
    }
}

@Composable
private fun AgendaHeader(onClose: () -> Unit) {
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
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = HugeIcons.Task01,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "事项",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "今天还有 3 项待处理",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClose) {
            Icon(HugeIcons.Cancel01, contentDescription = "关闭事项边栏")
        }
    }
}

@Composable
private fun AgendaSummary() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SummaryPill(
            text = "3 待处理",
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        SummaryPill(
            text = "1 待提醒",
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        SummaryPill(
            text = "1 已逾期",
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun AgendaFilters(
    selected: AgendaFilter,
    onSelected: (AgendaFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(AgendaFilter.entries, key = { it.name }) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun AgendaItemList(
    items: List<AgendaPreviewItem>,
    completedIds: Set<String>,
    onToggleCompleted: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgendaSection.entries.forEach { section ->
            val sectionItems = items.filter { it.section(completedIds) == section }
            if (sectionItems.isNotEmpty()) {
                item(key = "section-${section.name}") {
                    Text(
                        text = section.label,
                        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (section == AgendaSection.OVERDUE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                items(sectionItems, key = { it.id }) { item ->
                    AgendaItemCard(
                        item = item,
                        completed = item.id in completedIds,
                        onToggleCompleted = { onToggleCompleted(item.id) },
                    )
                }
            }
        }

        if (items.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Task01,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("这里还没有事项", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "稍后可以在对话中创建提醒、待办或日历事件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgendaItemCard(
    item: AgendaPreviewItem,
    completed: Boolean,
    onToggleCompleted: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.kind == AgendaKind.TASK) {
                IconButton(onClick = onToggleCompleted) {
                    Icon(
                        imageVector = if (completed) HugeIcons.CheckmarkCircle02 else HugeIcons.Task01,
                        contentDescription = if (completed) "标记为待处理" else "标记为已完成",
                        tint = if (completed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            } else {
                Surface(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(40.dp),
                    shape = CircleShape,
                    color = item.kind.containerColor(),
                    contentColor = item.kind.contentColor(),
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = item.kind.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (completed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = item.time,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.section == AgendaSection.OVERDUE && !completed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AgendaStatusLabel(item = item, completed = completed)
            }
        }
    }
}

@Composable
private fun AgendaStatusLabel(item: AgendaPreviewItem, completed: Boolean) {
    val label = when {
        completed -> "已完成"
        item.section == AgendaSection.OVERDUE -> "已逾期"
        item.kind == AgendaKind.REMINDER -> "待提醒"
        item.kind == AgendaKind.EVENT -> "日历"
        else -> "待处理"
    }
    val containerColor = when {
        completed -> MaterialTheme.colorScheme.secondaryContainer
        item.section == AgendaSection.OVERDUE -> MaterialTheme.colorScheme.errorContainer
        item.kind == AgendaKind.REMINDER -> MaterialTheme.colorScheme.tertiaryContainer
        item.kind == AgendaKind.EVENT -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        completed -> MaterialTheme.colorScheme.onSecondaryContainer
        item.section == AgendaSection.OVERDUE -> MaterialTheme.colorScheme.onErrorContainer
        item.kind == AgendaKind.REMINDER -> MaterialTheme.colorScheme.onTertiaryContainer
        item.kind == AgendaKind.EVENT -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun AgendaKind.icon(): ImageVector = when (this) {
    AgendaKind.TASK -> HugeIcons.Task01
    AgendaKind.REMINDER -> HugeIcons.AlarmClock
    AgendaKind.EVENT -> HugeIcons.Calendar03
}

@Composable
private fun AgendaKind.containerColor(): Color = when (this) {
    AgendaKind.TASK -> MaterialTheme.colorScheme.surfaceContainerHighest
    AgendaKind.REMINDER -> MaterialTheme.colorScheme.tertiaryContainer
    AgendaKind.EVENT -> MaterialTheme.colorScheme.primaryContainer
}

@Composable
private fun AgendaKind.contentColor(): Color = when (this) {
    AgendaKind.TASK -> MaterialTheme.colorScheme.onSurfaceVariant
    AgendaKind.REMINDER -> MaterialTheme.colorScheme.onTertiaryContainer
    AgendaKind.EVENT -> MaterialTheme.colorScheme.onPrimaryContainer
}

private enum class AgendaFilter(val label: String) {
    TODAY("今天"),
    ALL("全部"),
    TASKS("待办"),
    CALENDAR("日历"),
    COMPLETED("已完成"),
}

private enum class AgendaSection(val label: String) {
    OVERDUE("逾期"),
    TODAY("今天"),
    UPCOMING("接下来"),
    COMPLETED("已完成"),
}

private enum class AgendaKind {
    TASK,
    REMINDER,
    EVENT,
}

private data class AgendaPreviewItem(
    val id: String,
    val title: String,
    val time: String,
    val kind: AgendaKind,
    val section: AgendaSection,
) {
    fun section(completedIds: Set<String>): AgendaSection =
        if (id in completedIds) AgendaSection.COMPLETED else section
}

private val agendaPreviewItems = listOf(
    AgendaPreviewItem(
        id = "overdue-report",
        title = "提交季度总结",
        time = "昨天 18:00 截止",
        kind = AgendaKind.TASK,
        section = AgendaSection.OVERDUE,
    ),
    AgendaPreviewItem(
        id = "review-meeting",
        title = "产品评审会议",
        time = "14:30–15:30 · 第三会议室",
        kind = AgendaKind.EVENT,
        section = AgendaSection.TODAY,
    ),
    AgendaPreviewItem(
        id = "call-family",
        title = "给父母打电话",
        time = "今天 20:00 提醒",
        kind = AgendaKind.REMINDER,
        section = AgendaSection.TODAY,
    ),
    AgendaPreviewItem(
        id = "issue-90-plan",
        title = "整理 #90 页面方案",
        time = "今天",
        kind = AgendaKind.TASK,
        section = AgendaSection.TODAY,
    ),
    AgendaPreviewItem(
        id = "dentist",
        title = "预约牙医",
        time = "明天 10:00",
        kind = AgendaKind.TASK,
        section = AgendaSection.UPCOMING,
    ),
    AgendaPreviewItem(
        id = "completed-plan",
        title = "确认下周安排",
        time = "今天 09:30 完成",
        kind = AgendaKind.TASK,
        section = AgendaSection.TODAY,
    ),
)
