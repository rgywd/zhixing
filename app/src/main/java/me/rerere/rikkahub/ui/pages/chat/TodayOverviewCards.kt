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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.status.MyStatusCoordinator
import me.rerere.rikkahub.data.status.MyStatusSnapshot
import me.rerere.rikkahub.data.status.buildMyStatusDiscussionDraft
import me.rerere.rikkahub.data.today.TodayOverviewProvider
import me.rerere.rikkahub.data.work.PhoneWorkSession
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.compose.koinInject

/**
 * 空会话态的「现在值得注意」卡片组:Work 等待、事项行动、当前状态摘要。
 * 没有任何值得注意的内容时整体不渲染,保持空态留白。
 */
@Composable
internal fun TodayOverviewCards(
    onOpenAgenda: () -> Unit,
    modifier: Modifier = Modifier,
    provider: TodayOverviewProvider = koinInject(),
    coordinator: MyStatusCoordinator = koinInject(),
) {
    val snapshot by provider.state.collectAsStateWithLifecycle()
    val statusState by coordinator.state.collectAsStateWithLifecycle()
    LaunchedEffect(provider) { provider.onVisible() }

    val statusSnapshot = statusState.snapshot
    if (snapshot.isEmpty && statusSnapshot == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (snapshot.waitingSessions.isNotEmpty()) {
            WorkWaitingCard(waitingSessions = snapshot.waitingSessions)
        }
        if (snapshot.agendaActionCount > 0) {
            AgendaActionBar(
                actionCount = snapshot.agendaActionCount,
                overdueCount = snapshot.agendaOverdueCount,
                onClick = onOpenAgenda,
            )
        }
        if (statusSnapshot != null) {
            CompactStatusCard(snapshot = statusSnapshot)
        }
    }
}

@Composable
private fun WorkWaitingCard(waitingSessions: List<PhoneWorkSession>) {
    val navigator = LocalNavController.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (waitingSessions.size == 1) {
                    navigator.navigate(Screen.PhoneWorkSession(waitingSessions.single().id))
                } else {
                    navigator.navigate(Screen.PhoneWorkHome)
                }
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Lucide.MessageCircleQuestion,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${waitingSessions.size} 个会话等你回答",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                if (waitingSessions.size == 1) {
                    val session = waitingSessions.single()
                    Text(
                        text = session.title.ifBlank { session.repoName },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun AgendaActionBar(
    actionCount: Int,
    overdueCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Lucide.ListChecks,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "$actionCount 项需处理",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (overdueCount > 0) {
                    Text(
                        text = "· 含逾期 $overdueCount",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompactStatusCard(
    snapshot: MyStatusSnapshot,
    coordinator: MyStatusCoordinator = koinInject(),
) {
    val navigator = LocalNavController.current
    LaunchedEffect(coordinator) { coordinator.onVisible() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "现在最值得注意",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = snapshot.summary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            snapshot.recommendation?.let { recommendation ->
                Text(
                    text = recommendation.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        navigateToChatPage(
                            navigator = navigator,
                            initText = buildMyStatusDiscussionDraft(snapshot).base64Encode(),
                        )
                    },
                ) {
                    Text("聊聊")
                }
            }
        }
    }
}
