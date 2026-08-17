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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.HealthMetricType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.stats.HealthStatsUiState

internal const val STATS_HEALTH_TAB_INDEX = 2

@Composable
internal fun HealthGlanceCard(
    stats: HealthStatsUiState,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavController.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { navigator.navigate(Screen.Stats(initialTab = STATS_HEALTH_TAB_INDEX)) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Favourite,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "健康",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = "查看全部",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HealthGlanceMetric(
                    label = "步数",
                    value = formatGlanceSteps(stats.metrics[HealthMetricType.STEPS]?.valueDecimal),
                    modifier = Modifier.weight(1f),
                )
                HealthGlanceMetric(
                    label = "睡眠",
                    value = formatGlanceSleepMinutes(stats.metrics[HealthMetricType.SLEEP_MINUTES]?.valueDecimal),
                    modifier = Modifier.weight(1f),
                )
                HealthGlanceMetric(
                    label = "心率",
                    value = formatGlanceHeartRate(stats.metrics[HealthMetricType.HEART_RATE_BPM]?.valueDecimal),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HealthGlanceMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

internal fun formatGlanceSteps(valueDecimal: String?): String =
    valueDecimal?.toBigDecimalOrNull()
        ?.let { String.format(Locale.CHINA, "%,d", it.toBigInteger()) }
        ?: "--"

internal fun formatGlanceSleepMinutes(valueDecimal: String?): String {
    val minutes = valueDecimal?.toBigDecimalOrNull()?.toInt() ?: return "--"
    return when {
        minutes <= 0 -> "0 分钟"
        minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "${minutes / 60}小时${minutes % 60}分"
    }
}

internal fun formatGlanceHeartRate(valueDecimal: String?): String =
    valueDecimal?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()?.let { "$it bpm" } ?: "--"
