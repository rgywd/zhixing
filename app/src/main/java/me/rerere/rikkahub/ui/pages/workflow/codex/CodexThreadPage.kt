package me.rerere.rikkahub.ui.pages.workflow.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.workflow.codex.CodexApproval
import me.rerere.rikkahub.data.workflow.codex.CodexItem
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CodexThreadPage(
    machineId: String,
    threadId: String,
    vm: CodexThreadVM = koinViewModel(parameters = { parametersOf(machineId, threadId) }),
) {
    val navController = LocalNavController.current
    val detail = vm.detail
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(vm.deleted) {
        if (vm.deleted) navController.popBackStack()
    }
    if (vm.showTakeoverConfirmation) {
        AlertDialog(
            onDismissRequest = vm::dismissTakeover,
            title = { Text("继续这个桌面任务？") },
            text = { Text("此任务当前没有知行运行连接。继续后将由开发机上的 zhixing-agent 接管同一个 Codex 任务，不会新建第二条对话。") },
            confirmButton = { TextButton(onClick = vm::confirmTakeover) { Text("继续") } },
            dismissButton = { TextButton(onClick = vm::dismissTakeover) { Text("取消") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("永久删除任务？") },
            text = { Text("这会同时删除开发机上的 Codex 历史。通常建议先归档。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            detail.thread?.name ?: "任务",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            listOfNotNull(
                                "重点".takeIf { detail.thread?.isPinned == true },
                                if (vm.isRunning) "正在执行" else "Codex 任务",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (vm.isRunning) TextButton(onClick = vm::interrupt) { Text("停止") }
                    Box {
                        TextButton(onClick = { menuExpanded = true }) { Text("管理") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(if (detail.thread?.isPinned == true) "取消重点" else "设为重点") },
                                onClick = {
                                    menuExpanded = false
                                    vm.setPinned(detail.thread?.isPinned != true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (detail.thread?.archived == true) "取消归档" else "归档") },
                                onClick = {
                                    menuExpanded = false
                                    if (detail.thread?.archived == true) vm.unarchive() else vm.archive()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("永久删除", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuExpanded = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    vm.statusMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = vm.draft,
                            onValueChange = vm::updateDraft,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 144.dp),
                            placeholder = { Text(if (vm.isRunning) "补充要求" else "输入消息与 Codex 继续") },
                            minLines = 1,
                            maxLines = 5,
                        )
                        Button(
                            onClick = vm::send,
                            enabled = vm.canSend,
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) { Text("发送") }
                    }
                }
            }
        },
    ) { padding ->
        val conversationItems = detail.turns.flatMap { it.items }
        val listState = rememberLazyListState()
        val totalRows = conversationItems.size + detail.approvals.size
        LaunchedEffect(totalRows) {
            if (totalRows > 0) listState.animateScrollToItem(totalRows - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (conversationItems.isEmpty()) {
                item {
                    Text(
                        detail.thread?.preview?.ifBlank { "正在同步完整内容…" } ?: "任务不在当前缓存中",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(conversationItems, key = CodexItem::itemId) { item -> CodexItemRow(item) }
            items(detail.approvals, key = CodexApproval::approvalId) { approval ->
                ApprovalCard(
                    approval = approval,
                    onAccept = { vm.resolveApproval(approval.approvalId, "accept") },
                    onDecline = { vm.resolveApproval(approval.approvalId, "decline") },
                )
            }
        }
    }
}

@Composable
private fun CodexItemRow(item: CodexItem) {
    val text = item.text?.takeIf(String::isNotBlank) ?: return
    val isUser = item.role == "user"
    val isTool = item.role == "tool"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = if (isUser) 320.dp else 560.dp),
            shape = RoundedCornerShape(if (isTool) 12.dp else 18.dp),
            tonalElevation = if (isUser || isTool) 2.dp else 0.dp,
            color = when {
                isUser -> MaterialTheme.colorScheme.primaryContainer
                isTool -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            },
        ) {
            Column(modifier = Modifier.padding(if (isTool) 12.dp else 16.dp)) {
                if (isTool) {
                    Text("活动", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(text, style = if (isTool) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: CodexApproval, onAccept: () -> Unit, onDecline: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("需要你的确认", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(approval.summary, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text("允许一次") }
                TextButton(onClick = onDecline) { Text("拒绝") }
            }
        }
    }
}
