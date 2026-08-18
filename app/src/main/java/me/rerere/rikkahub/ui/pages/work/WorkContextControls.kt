package me.rerere.rikkahub.ui.pages.work

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.rikkahub.data.work.PhoneWorkContextUsagePayload
import me.rerere.rikkahub.data.work.PhoneWorkEvent
import me.rerere.rikkahub.data.work.PhoneWorkRunnerCapabilities

internal enum class WorkControlAction(val wireName: String, val slashCommand: String) {
    COMPACT("COMPACT", "/compact"),
    CONTEXT("CONTEXT", "/context"),
}

internal data class WorkContextUsageSnapshot(
    val usedTokens: Long,
    val contextWindow: Long,
) {
    val fraction: Float = if (contextWindow > 0) {
        (usedTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent: Int = (fraction * 100).toInt()
}

internal fun latestWorkContextUsage(events: List<PhoneWorkEvent>): WorkContextUsageSnapshot? {
    val json = Json { ignoreUnknownKeys = true }
    return events.asReversed().firstNotNullOfOrNull { event ->
        if (event.type != "CONTEXT_USAGE") return@firstNotNullOfOrNull null
        runCatching { json.decodeFromJsonElement<PhoneWorkContextUsagePayload>(event.payload) }
            .getOrNull()
            ?.takeIf { it.contextWindow > 0 && it.usedTokens >= 0 }
            ?.let { WorkContextUsageSnapshot(it.usedTokens, it.contextWindow) }
    }
}

internal fun parseWorkControlAction(text: String, runtime: String): WorkControlAction? {
    val command = text.trim().lowercase()
    return when (runtime) {
        "codex" -> WorkControlAction.COMPACT.takeIf { command == it.slashCommand }
        "claude-code" -> WorkControlAction.entries.firstOrNull { command == it.slashCommand }
        else -> null
    }
}

internal fun PhoneWorkRunnerCapabilities.supports(action: WorkControlAction, runtime: String): Boolean =
    when (runtime to action) {
        "codex" to WorkControlAction.COMPACT -> codexCompact
        "claude-code" to WorkControlAction.COMPACT -> claudeCompact
        "claude-code" to WorkControlAction.CONTEXT -> claudeContext
        else -> false
    }

@Composable
internal fun WorkContextRing(
    usage: WorkContextUsageSnapshot?,
    onClick: () -> Unit,
) {
    val fraction = usage?.fraction ?: 0f
    val progressColor = when {
        fraction >= 0.92f -> MaterialTheme.colorScheme.error
        fraction >= 0.80f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics {
            contentDescription = usage?.let { "上下文已用 ${it.percent}%" } ?: "上下文用量暂不可用"
        },
    ) {
        Canvas(Modifier.size(19.dp)) {
            val strokeWidth = 2.5.dp.toPx()
            val inset = strokeWidth / 2
            drawCircle(
                color = trackColor,
                radius = size.minDimension / 2 - inset,
                center = center,
                style = Stroke(width = strokeWidth),
            )
            if (fraction > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
internal fun WorkContextDetailsSheet(
    usage: WorkContextUsageSnapshot?,
    canCompact: Boolean,
    compacting: Boolean,
    onCompact: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("上下文", style = MaterialTheme.typography.titleLarge)
            if (usage == null) {
                Text(
                    "完成一次 Codex 交互后，这里会显示上下文用量。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${usage.percent}% 已用", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "${usage.usedTokens.compactTokenCount()} / ${usage.contextWindow.compactTokenCount()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "已用 ${usage.usedTokens.compactTokenCount()} 标记，共 ${usage.contextWindow.compactTokenCount()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(
                onClick = onCompact,
                enabled = canCompact && !compacting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (compacting) "正在提交…" else "压缩上下文")
            }
            if (!canCompact) {
                Text(
                    "压缩仅在任务空闲时可用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun Long.compactTokenCount(): String = when {
    this >= 1_000_000 -> "${(this / 100_000) / 10.0}m"
    this >= 1_000 -> "${(this / 100) / 10.0}k"
    else -> toString()
}
