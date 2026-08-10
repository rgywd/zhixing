package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessageAnnotation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun RuntimeContextCard(
    context: UIMessageAnnotation.RuntimeContext,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var evidenceExpanded by rememberSaveable(context.generatedAtEpochMillis) { mutableStateOf(false) }
    val expired = context.validUntilEpochMillis <= System.currentTimeMillis()
    val time = Instant.ofEpochMilli(context.generatedAtEpochMillis)
        .atZone(ZoneId.systemDefault())
        .format(CONTEXT_TIME_FORMATTER)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (expired) "来自当前状态 · 已过期" else "来自当前状态 · $time 更新",
                style = MaterialTheme.typography.labelMedium,
                color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = context.summary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            context.recommendation?.takeIf { !expired }?.let { recommendation ->
                Text(recommendation, style = MaterialTheme.typography.bodyMedium)
            }
            if (evidenceExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (context.evidence.isEmpty()) {
                    Text(
                        "暂无可带入的依据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    context.evidence.forEach { evidence ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = evidence.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(evidence.value, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    evidence.freshness,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { evidenceExpanded = !evidenceExpanded }) {
                    Text(if (evidenceExpanded) "收起依据" else "查看依据")
                }
                TextButton(onClick = onDismiss) {
                    Text("移除")
                }
            }
        }
    }
}

private val CONTEXT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
