package me.rerere.rikkahub.ui.pages.chat

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.status.MyStatusCoordinator
import me.rerere.rikkahub.data.status.MyStatusInsight
import me.rerere.rikkahub.data.status.MyStatusSnapshot
import me.rerere.rikkahub.data.status.buildMyStatusRuntimeContext
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun MyStatusCard(
    coordinator: MyStatusCoordinator = koinInject(),
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    var evidenceExpanded by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        coordinator.onLocationPermissionResult()
    }
    LaunchedEffect(coordinator) {
        coordinator.onVisible()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val snapshot = state.snapshot
            StatusContextLine(
                snapshot = snapshot,
                locationPermissionRequired = state.locationPermissionRequired,
            )
            if (snapshot == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在形成当前状态…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val expired = snapshot.validUntilEpochMillis <= System.currentTimeMillis()
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "现在最值得注意",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        snapshot.summary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                snapshot.insights.take(2).forEach { insight ->
                    StatusInsightLine(insight)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "此刻",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        when {
                            expired -> "状态可能已过时，请先更新。"
                            snapshot.recommendation != null -> snapshot.recommendation.text
                            else -> "当前没有需要介入的事情，按自己的节奏即可。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        generatedAtLabel(snapshot),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                            Text(
                                if (expired) {
                                    "更新状态"
                                } else {
                                    "聊聊"
                                }
                            )
                        }
                        TextButton(onClick = { evidenceExpanded = !evidenceExpanded }) {
                            Text(if (evidenceExpanded) "收起依据" else "查看依据")
                        }
                    }
                }
                if (evidenceExpanded) {
                    StatusEvidenceList(snapshot)
                }
            }

            if (state.locationPermissionRequired) {
                TextButton(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    },
                ) {
                    Text("允许粗略位置，用于本地天气")
                }
            }
            state.statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusContextLine(
    snapshot: MyStatusSnapshot?,
    locationPermissionRequired: Boolean,
) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(snapshot?.generatedAtEpochMillis) {
        snapshot?.generatedAtEpochMillis
            ?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
            ?: ZonedDateTime.now(zone)
    }
    val weekday = WEEKDAYS.getOrElse(now.dayOfWeek.value - 1) { "" }
    val location = snapshot?.locationArea
        ?: if (locationPermissionRequired) "位置未授权" else "位置暂不可用"
    Text(
        text = buildString {
            append(lifeOverviewGreeting(now.hour))
            append(" · ${now.monthValue}月${now.dayOfMonth}日 $weekday ")
            append(STATUS_TIME_FORMATTER.format(now))
            append(" · $location")
        },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun StatusInsightLine(insight: MyStatusInsight) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            insight.kind.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(insight.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusEvidenceList(snapshot: MyStatusSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        snapshot.evidence.forEach { evidence ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
        if (snapshot.evidence.isEmpty()) {
            Text(
                "暂无可用依据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun generatedAtLabel(snapshot: MyStatusSnapshot): String {
    val time = Instant.ofEpochMilli(snapshot.generatedAtEpochMillis)
        .atZone(ZoneId.systemDefault())
    val source = if (snapshot.source.name == "AI") "快速模型" else "本地判断"
    return "$source · 更新于 ${STATUS_TIME_FORMATTER.format(time)}"
}

private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val STATUS_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
