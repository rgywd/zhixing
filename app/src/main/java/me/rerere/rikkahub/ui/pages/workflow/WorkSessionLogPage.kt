package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.Instant
import me.rerere.rikkahub.data.workflow.WorkMessage
import me.rerere.rikkahub.data.workflow.WorkMessagePart
import me.rerere.rikkahub.data.workflow.WorkRole
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.toLocalDateTime
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class LogFilter(val label: String) {
    ALL("全部"),
    TOOLS("工具"),
    FILES("文件"),
    TERMINAL("终端"),
    EVENTS("事件"),
}

@Composable
fun WorkSessionLogPage(
    sessionId: String,
    vm: WorkflowSessionVM = koinViewModel(parameters = { parametersOf(sessionId) }),
) {
    LocalNavController.current
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val entries = remember(vm.messages, filter) { buildLogEntries(vm.messages, filter) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("完整日志") },
                navigationIcon = { BackButton() },
            )
        },
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogFilter.entries.forEach { candidate ->
                    FilterChip(
                        selected = filter == candidate,
                        onClick = { filter = candidate },
                        label = { Text(candidate.label) },
                    )
                }
            }
            when {
                vm.isLoading && entries.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("该筛选下暂无日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = LogEntry::key) { entry -> LogEntryRow(entry) }
                }
            }
        }
    }
}

private data class LogEntry(
    val key: String,
    val time: Long,
    val kind: String,
    val title: String,
    val body: String?,
    val isError: Boolean = false,
    val monospace: Boolean = false,
)

private fun buildLogEntries(messages: List<WorkMessage>, filter: LogFilter): List<LogEntry> {
    val entries = mutableListOf<LogEntry>()
    messages.forEach { message ->
        message.parts.forEachIndexed { index, part ->
            val key = "${message.id}-$index"
            val entry = when (part) {
                is WorkMessagePart.Text -> LogEntry(
                    key = key,
                    time = message.createdAt,
                    kind = if (message.role == WorkRole.USER) "用户" else "回复",
                    title = if (message.role == WorkRole.USER) "用户消息" else "Agent 回复",
                    body = part.text,
                ).takeIf { filter == LogFilter.ALL }
                is WorkMessagePart.Reasoning -> LogEntry(
                    key = key,
                    time = message.createdAt,
                    kind = "思考",
                    title = "思考",
                    body = part.text,
                ).takeIf { filter == LogFilter.ALL }
                is WorkMessagePart.ToolCall -> LogEntry(
                    key = key,
                    time = message.createdAt,
                    kind = "工具",
                    title = part.title ?: part.name,
                    body = part.input.takeIf(String::isNotBlank),
                    monospace = true,
                ).takeIf { filter == LogFilter.ALL || filter == LogFilter.TOOLS }
                is WorkMessagePart.ToolResult -> LogEntry(
                    key = key,
                    time = message.createdAt,
                    kind = "工具",
                    title = if (part.isError) "执行失败" else "执行结果",
                    body = part.output.takeIf(String::isNotBlank),
                    isError = part.isError,
                    monospace = true,
                ).takeIf { filter == LogFilter.ALL || filter == LogFilter.TOOLS }
                is WorkMessagePart.FileEdit -> LogEntry(
                    key = key,
                    time = message.createdAt,
                    kind = "文件",
                    title = "修改 ${part.filePath}",
                    body = part.diff ?: part.description,
                    monospace = part.diff != null,
                ).takeIf { filter == LogFilter.ALL || filter == LogFilter.FILES }
                is WorkMessagePart.Terminal -> LogEntry(
                    key = key,
                    time = message.createdAt,
                    kind = "终端",
                    title = "终端输出",
                    body = part.output,
                    monospace = true,
                ).takeIf { filter == LogFilter.ALL || filter == LogFilter.TERMINAL }
                is WorkMessagePart.Event -> LogEntry(
                    key = key,
                    time = message.createdAt,
                    kind = "事件",
                    title = part.kind,
                    body = part.text,
                ).takeIf { filter == LogFilter.ALL || filter == LogFilter.EVENTS }
                is WorkMessagePart.ClaudeAsk -> LogEntry(
                    key = key,
                    time = message.createdAt,
                    kind = "提问",
                    title = "等待用户答复",
                    body = part.prompt,
                ).takeIf { filter == LogFilter.ALL || filter == LogFilter.EVENTS }
                is WorkMessagePart.HtmlReport -> LogEntry(
                    key = key,
                    time = message.createdAt,
                    kind = "报告",
                    title = part.title,
                    body = "HTML 报告内容仅在只读报告页展示",
                ).takeIf { filter == LogFilter.ALL || filter == LogFilter.EVENTS }
                is WorkMessagePart.Raw -> LogEntry(
                    key = key,
                    time = message.createdAt,
                    kind = "事件",
                    title = part.kind,
                    body = part.text,
                ).takeIf { filter == LogFilter.ALL || filter == LogFilter.EVENTS }
            }
            entry?.let(entries::add)
        }
    }
    return entries
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (entry.isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.kind,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(
                    Instant.ofEpochMilli(entry.time).toLocalDateTime(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
            entry.body?.takeIf(String::isNotBlank)?.let { body ->
                Text(
                    body.take(2000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (entry.monospace) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
