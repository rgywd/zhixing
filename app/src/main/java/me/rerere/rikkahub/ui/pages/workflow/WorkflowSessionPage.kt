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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import me.rerere.asr.ASRStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ai.AsrButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.workflow.WorkApproval
import me.rerere.rikkahub.data.workflow.WorkChatItem
import me.rerere.rikkahub.data.workflow.WorkSession
import me.rerere.rikkahub.data.workflow.buildWorkChatItems
import me.rerere.rikkahub.data.workflow.sessionStats
import me.rerere.rikkahub.ui.components.message.MessagePartsBlock
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
    val displayItems = remember(vm.messages) { buildWorkChatItems(vm.messages) }
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
                    WorkSessionInput(
                        vm = vm,
                        onRequestFullAccess = { modeConfirmation = true },
                    )
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
                items(displayItems, key = WorkChatItem::key) { item ->
                    when (item) {
                        is WorkChatItem.PartsBlock -> MessagePartsBlock(
                            assistant = null,
                            role = item.role,
                            model = null,
                            parts = item.parts,
                            annotations = emptyList(),
                            loading = false,
                        )
                        is WorkChatItem.Note -> Box(
                            Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            MarkdownBlock(
                                content = item.text,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
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

/**
 * 会话输入区：对齐主页对话框的视觉与交互（圆角容器、语音听写、圆形发送键），
 * 附加远程会话特有的执行模式 chip。附件上传等待协议侧 file upload 支持后接入。
 */
@Composable
private fun WorkSessionInput(
    vm: WorkflowSessionVM,
    onRequestFullAccess: () -> Unit,
) {
    val asr = LocalASRState.current
    val asrState by asr.state.collectAsState()
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    var asrBaseText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.largeIncreased,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    value = vm.draft,
                    onValueChange = vm::updateDraft,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("补充要求") },
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.send() }),
                    enabled = vm.session != null && !vm.isActing,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            if (vm.fullAccess) vm.updateFullAccess(false) else onRequestFullAccess()
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
                        if (vm.fullAccess) "自动执行中" else "写操作需确认",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (asrState.isAvailable || asrState.isRecording) {
                        AsrButton(
                            state = asrState,
                            onClick = {
                                when (asrState.status) {
                                    ASRStatus.Listening -> asr.stop()
                                    ASRStatus.Idle, ASRStatus.Error -> {
                                        if (!asrPermission.allRequiredPermissionsGranted) {
                                            asrPermission.requestPermissions()
                                        } else {
                                            asrBaseText = vm.draft
                                            asr.start { transcript ->
                                                val spacer =
                                                    if (asrBaseText.isBlank() || transcript.isBlank()) "" else " "
                                                vm.updateDraft(asrBaseText + spacer + transcript)
                                            }
                                        }
                                    }
                                    ASRStatus.Connecting, ASRStatus.Stopping -> {}
                                }
                            },
                        )
                    }
                    val canSend = vm.draft.isNotBlank() && vm.session != null && !vm.isActing
                    Surface(
                        onClick = vm::send,
                        enabled = canSend,
                        shape = CircleShape,
                        color = if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Icon(
                            HugeIcons.ArrowUp02,
                            contentDescription = "发送",
                            modifier = Modifier.padding(6.dp),
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
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
