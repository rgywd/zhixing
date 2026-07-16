package me.rerere.rikkahub.ui.pages.workflow

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyApproval
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyMessage
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyMessageRole
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowSessionPage(
    sessionId: String,
    vm: WorkflowSessionVM = koinViewModel(parameters = { parametersOf(sessionId) }),
) {
    var stopConfirmation by remember { mutableStateOf(false) }
    var approvalConfirmation by remember { mutableStateOf<ApprovalConfirmation?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.session?.approvals?.size) {
        val lastIndex = vm.messages.size + vm.session?.approvals.orEmpty().size - 1
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
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
                    TextButton(onClick = { stopConfirmation = true }, enabled = vm.session != null && !vm.isActing) {
                        Text("停止", color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = vm.draft,
                        onValueChange = vm::updateDraft,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("给 Codex 补充要求") },
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
        },
    ) { padding ->
        if (vm.isLoading && vm.messages.isEmpty()) {
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
                item { SessionTargetCard(vm) }
                vm.error?.let { message -> item { StatusCard(message, isError = true, vm::refresh) } }
                vm.notice?.let { message -> item { StatusCard(message, isError = false) } }
                items(vm.messages, key = HappyMessage::id) { message -> MessageBubble(message) }
                items(vm.session?.approvals.orEmpty(), key = HappyApproval::id) { approval ->
                    ApprovalCard(approval) { decision ->
                        approvalConfirmation = ApprovalConfirmation(approval, decision)
                    }
                }
                if (vm.messages.isEmpty() && vm.error == null) {
                    item {
                        Text(
                            "暂无消息。可直接发送要求，消息会通过 Happy 端到端加密转给开发机。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (stopConfirmation) {
        ConfirmationDialog(
            title = "停止这个远程任务？",
            summary = vm.session.targetSummary() + "\n操作：请求 Codex 停止当前任务",
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
private fun SessionTargetCard(vm: WorkflowSessionVM) {
    val session = vm.session ?: return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("远程操作目标", fontWeight = FontWeight.SemiBold)
            Text(session.targetSummary(), style = MaterialTheme.typography.bodySmall)
            Text(
                if (session.active) "开发机连接活跃" else "开发机当前离线，读取仍会自动补偿",
                style = MaterialTheme.typography.labelMedium,
                color = if (session.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
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
private fun MessageBubble(message: HappyMessage) {
    val isUser = message.role == HappyMessageRole.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Card(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.94f),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (message.kind !in setOf("text", "message")) {
                    Text(message.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(message.text)
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: HappyApproval, onDecision: (ApprovalDecision) -> Unit) {
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

private fun me.rerere.rikkahub.ui.pages.workflow.happy.HappySession?.targetSummary(): String {
    val session = this ?: return "会话信息仍在同步"
    return listOfNotNull(
        "机器：${session.host ?: "未知"}",
        session.path?.let { "项目：$it" },
        "会话：${session.displayTitle()} (${session.id.take(8)})",
    ).joinToString("\n")
}

private fun me.rerere.rikkahub.ui.pages.workflow.happy.HappySession.displayTitle(): String =
    name ?: path?.substringAfterLast('/')?.substringAfterLast('\\') ?: "会话 ${id.take(8)}"

private data class ApprovalConfirmation(val approval: HappyApproval, val decision: ApprovalDecision)

private enum class ApprovalDecision(val title: String, val confirmText: String) {
    ALLOW_ONCE("允许这一次操作？", "允许一次"),
    ALLOW_SESSION("本会话持续允许该操作？", "本会话允许"),
    DENY("拒绝这次操作？", "确认拒绝"),
    DENY_AND_STOP("拒绝并停止整个任务？", "拒绝并停止"),
}
