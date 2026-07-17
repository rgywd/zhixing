package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.workflow.WorkApproval
import me.rerere.rikkahub.data.workflow.WorkDisplayItem
import me.rerere.rikkahub.data.workflow.WorkSession
import me.rerere.rikkahub.data.workflow.buildDisplayItems
import me.rerere.rikkahub.data.workflow.sessionStats
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowSessionPage(
    sessionId: String,
    vm: WorkflowSessionVM = koinViewModel(parameters = { parametersOf(sessionId) }),
) {
    val navController = LocalNavController.current
    var stopConfirmation by remember { mutableStateOf(false) }
    var modeConfirmation by remember { mutableStateOf(false) }
    var approvalConfirmation by remember { mutableStateOf<ApprovalConfirmation?>(null) }
    val listState = rememberLazyListState()
    val displayItems = remember(vm.messages) { buildDisplayItems(vm.messages) }
    val stats = remember(vm.messages) { sessionStats(vm.messages) }

    LaunchedEffect(displayItems.size, vm.session?.approvals?.size) {
        val lastIndex = displayItems.size + vm.session?.approvals.orEmpty().size
        if (lastIndex > 0) listState.animateScrollToItem(lastIndex)
    }
    LaunchedEffect(vm.resumedSessionId) {
        vm.resumedSessionId?.let { id ->
            vm.consumeResumedSession()
            navController.navigate(Screen.WorkflowSession(id))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    Column {
                        Text(vm.session?.displayTitle() ?: "远程会话")
                        vm.session?.host?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.WorkSessionLog(sessionId)) }) {
                        Icon(HugeIcons.File02, contentDescription = "完整日志")
                    }
                    TextButton(
                        onClick = { stopConfirmation = true },
                        enabled = vm.session?.active == true && !vm.isActing,
                    ) {
                        Text("停止", color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                if (vm.session?.active == false) {
                    Button(
                        onClick = vm::resumeSession,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        enabled = !vm.isActing && vm.machines.any { it.id == vm.session?.machineId && it.active },
                    ) {
                        Text(if (vm.isActing) "正在恢复" else "在开发机上恢复此对话")
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    if (vm.fullAccess) vm.updateFullAccess(false)
                                    else modeConfirmation = true
                                },
                                enabled = !vm.isActing,
                            ) {
                                Text(
                                    if (vm.fullAccess) "⚡ 完全访问" else "普通模式",
                                    color = if (vm.fullAccess) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Text(
                                if (vm.fullAccess) "自动执行中，仅硬性限制与失败会打扰你"
                                else "写操作与命令需要你的确认",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = vm.draft,
                                onValueChange = vm::updateDraft,
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("补充要求") },
                                minLines = 1,
                                maxLines = 5,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { vm.send() }),
                                enabled = vm.session != null && !vm.isActing,
                            )
                            Button(
                                onClick = vm::send,
                                enabled = vm.draft.isNotBlank() && vm.session != null && !vm.isActing,
                            ) {
                                Text("发送")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (vm.isLoading && displayItems.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "target") {
                    SessionTargetCard(
                        vm = vm,
                        summary = statsSummary(stats),
                        onOpenLog = { navController.navigate(Screen.WorkSessionLog(sessionId)) },
                    )
                }
                vm.error?.let { message -> item(key = "error") { StatusCard(message, isError = true, vm::refresh) } }
                vm.notice?.let { message -> item(key = "notice") { StatusCard(message, isError = false) } }
                items(displayItems, key = WorkDisplayItem::key) { item -> DisplayItemContent(item) }
                items(vm.session?.approvals.orEmpty(), key = WorkApproval::id) { approval ->
                    ApprovalCard(approval) { decision ->
                        approvalConfirmation = ApprovalConfirmation(approval, decision)
                    }
                }
                if (displayItems.isEmpty() && vm.error == null) {
                    item(key = "empty") {
                        Text(
                            "暂无消息。可直接发送要求，消息会通过 Happy 端到端加密转给开发机。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (modeConfirmation) {
        AlertDialog(
            onDismissRequest = { modeConfirmation = false },
            title = { Text("切换到完全访问？") },
            text = {
                Text(
                    "从下一条消息起，远端将自主执行读写、命令、测试和 Git 操作，不再逐步请求确认。" +
                        "仓库预设中的硬性限制仍然生效（仅 Claude Code 远端强制）。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    modeConfirmation = false
                    vm.updateFullAccess(true)
                }) { Text("切换") }
            },
            dismissButton = {
                TextButton(onClick = { modeConfirmation = false }) { Text("取消") }
            },
        )
    }
    if (stopConfirmation) {
        ConfirmationDialog(
            title = "停止这个远程任务？",
            summary = vm.session.targetSummary() + "\n操作：请求停止当前任务",
            confirmText = "确认停止",
            onDismiss = { stopConfirmation = false },
            onConfirm = {
                stopConfirmation = false
                vm.stop()
            },
        )
    }
    approvalConfirmation?.let { confirmation ->
        ConfirmationDialog(
            title = confirmation.decision.title,
            summary = vm.session.targetSummary() +
                "\n工具：${confirmation.approval.tool}" +
                "\n参数：${confirmation.approval.arguments.take(600)}",
            confirmText = confirmation.decision.confirmText,
            onDismiss = { approvalConfirmation = null },
            onConfirm = {
                approvalConfirmation = null
                when (confirmation.decision) {
                    ApprovalDecision.ALLOW_ONCE -> vm.approve(confirmation.approval, false)
                    ApprovalDecision.ALLOW_SESSION -> vm.approve(confirmation.approval, true)
                    ApprovalDecision.DENY -> vm.deny(confirmation.approval, false)
                    ApprovalDecision.DENY_AND_STOP -> vm.deny(confirmation.approval, true)
                }
            },
        )
    }
}

@Composable
private fun SessionTargetCard(vm: WorkflowSessionVM, summary: String?, onOpenLog: () -> Unit) {
    val session = vm.session ?: return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("远程操作目标", fontWeight = FontWeight.SemiBold)
            Text(session.targetSummary(), style = MaterialTheme.typography.bodySmall)
            Text(
                listOfNotNull(
                    if (session.active) "任务正在运行" else "任务已结束",
                    "⚡完全访问".takeIf { vm.fullAccess },
                    summary,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = if (session.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenLog, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("查看完整日志")
            }
        }
    }
}

@Composable
private fun DisplayItemContent(item: WorkDisplayItem) {
    when (item) {
        is WorkDisplayItem.UserText -> Bubble(text = item.text, isUser = true)
        is WorkDisplayItem.AgentText -> Bubble(text = item.text, isUser = false)
        is WorkDisplayItem.Thinking -> ThinkingCard(item)
        is WorkDisplayItem.Activity -> ActivityCard(item)
        is WorkDisplayItem.EventNote -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                item.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Bubble(text: String, isUser: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.94f),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(text, Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun ThinkingCard(item: WorkDisplayItem.Thinking) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (expanded) "思考" else "思考（点击展开）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (expanded) item.text else item.text.lineSequence().firstOrNull().orEmpty().take(80),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActivityCard(item: WorkDisplayItem.Activity) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val hasError = item.entries.any(me.rerere.rikkahub.data.workflow.WorkActivityEntry::isError)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (hasError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${item.entries.size} 个操作" + if (hasError) " · 有失败" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            val visible = if (expanded) item.entries else item.entries.take(3)
            visible.forEach { entry ->
                Column {
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (entry.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    if (expanded && !entry.detail.isNullOrBlank()) {
                        Text(
                            entry.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!expanded && item.entries.size > 3) {
                Text(
                    "还有 ${item.entries.size - 3} 个操作…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(message: String, isError: Boolean, retry: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            retry?.let { TextButton(onClick = it) { Text("重试") } }
        }
    }
}

@Composable
private fun ApprovalCard(approval: WorkApproval, onDecision: (ApprovalDecision) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("等待你的确认", fontWeight = FontWeight.Bold)
            Text(approval.tool, style = MaterialTheme.typography.titleSmall)
            Text(approval.arguments.take(600), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDecision(ApprovalDecision.ALLOW_ONCE) }) { Text("允许一次") }
                OutlinedButton(onClick = { onDecision(ApprovalDecision.ALLOW_SESSION) }) { Text("本会话允许") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onDecision(ApprovalDecision.DENY) }) { Text("拒绝") }
                TextButton(onClick = { onDecision(ApprovalDecision.DENY_AND_STOP) }) {
                    Text("拒绝并停止", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    summary: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(summary) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun statsSummary(stats: me.rerere.rikkahub.data.workflow.WorkSessionStats): String? {
    if (stats.toolCalls == 0 && stats.editedFiles == 0) return null
    return listOfNotNull(
        "${stats.toolCalls} 次工具调用".takeIf { stats.toolCalls > 0 },
        "${stats.editedFiles} 个文件修改".takeIf { stats.editedFiles > 0 },
        "${stats.failures} 次失败".takeIf { stats.failures > 0 },
    ).joinToString(" · ")
}

private fun WorkSession?.targetSummary(): String {
    val session = this ?: return "会话信息仍在同步"
    return listOfNotNull(
        "机器：${session.host ?: "未知"}",
        session.path?.let { "项目：$it" },
        "会话：${session.displayTitle()} (${session.id.take(8)})",
    ).joinToString("\n")
}

private fun WorkSession.displayTitle(): String =
    name ?: path?.substringAfterLast('/')?.substringAfterLast('\\') ?: "会话 ${id.take(8)}"

private data class ApprovalConfirmation(val approval: WorkApproval, val decision: ApprovalDecision)

private enum class ApprovalDecision(val title: String, val confirmText: String) {
    ALLOW_ONCE("允许这一次操作？", "允许一次"),
    ALLOW_SESSION("本会话持续允许该操作？", "本会话允许"),
    DENY("拒绝这次操作？", "确认拒绝"),
    DENY_AND_STOP("拒绝并停止整个任务？", "拒绝并停止"),
}
