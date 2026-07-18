package me.rerere.rikkahub.ui.pages.workflow.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.workflow.codex.CodexItem
import me.rerere.rikkahub.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CodexThreadPage(
    machineId: String,
    threadId: String,
    vm: CodexThreadVM = koinViewModel(parameters = { parametersOf(machineId, threadId) }),
) {
    val detail = vm.detail
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        detail.thread?.name ?: "任务",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        val items = detail.turns.flatMap { it.items }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    Text(
                        detail.thread?.preview?.ifBlank { "完整内容尚未同步，目录与标题仍可离线查看。" }
                            ?: "任务不在当前缓存中",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(items, key = CodexItem::itemId) { item ->
                item.text?.takeIf(String::isNotBlank)?.let { text ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = if (item.role == "user") 2.dp else 0.dp,
                        color = if (item.role == "user") MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    ) {
                        Text(
                            text,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (item.role == "system") FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
