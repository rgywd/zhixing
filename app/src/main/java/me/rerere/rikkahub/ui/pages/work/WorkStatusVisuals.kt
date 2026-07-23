package me.rerere.rikkahub.ui.pages.work

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Work 域统一的状态语义色：RUNNING 主色、等待回答用 tertiary 提到最高优先级、
 * 可继续用 secondary、失败用 error，排队与终态回落到中性色。
 */
@Composable
internal fun workStatusColor(status: String): Color = when (status) {
    "RUNNING" -> MaterialTheme.colorScheme.primary
    "WAITING_FOR_USER" -> MaterialTheme.colorScheme.tertiary
    "IDLE" -> MaterialTheme.colorScheme.secondary
    "FAILED" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun workStatusContainerColor(status: String): Color = when (status) {
    "RUNNING" -> MaterialTheme.colorScheme.primaryContainer
    "WAITING_FOR_USER" -> MaterialTheme.colorScheme.tertiaryContainer
    "IDLE" -> MaterialTheme.colorScheme.secondaryContainer
    "FAILED" -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
}

@Composable
internal fun workStatusOnContainerColor(status: String): Color = when (status) {
    "RUNNING" -> MaterialTheme.colorScheme.onPrimaryContainer
    "WAITING_FOR_USER" -> MaterialTheme.colorScheme.onTertiaryContainer
    "IDLE" -> MaterialTheme.colorScheme.onSecondaryContainer
    "FAILED" -> MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun WorkPulsingDot(color: Color, pulse: Boolean, modifier: Modifier = Modifier) {
    val pulseModifier = if (pulse) {
        val transition = rememberInfiniteTransition(label = "work-status-pulse")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "work-status-pulse-alpha",
        )
        Modifier.alpha(alpha)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .then(pulseModifier)
            .background(color, CircleShape),
    )
}

@Composable
internal fun WorkStatusDot(status: String, modifier: Modifier = Modifier) {
    WorkPulsingDot(color = workStatusColor(status), pulse = status == "RUNNING", modifier = modifier)
}

@Composable
internal fun WorkStatusChip(
    status: String,
    modifier: Modifier = Modifier,
    containerColor: Color = workStatusContainerColor(status),
    contentColor: Color = workStatusOnContainerColor(status),
    dotColor: Color = workStatusColor(status),
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkPulsingDot(color = dotColor, pulse = status == "RUNNING")
            Text(status.displayStatus(), style = MaterialTheme.typography.labelMedium)
        }
    }
}
