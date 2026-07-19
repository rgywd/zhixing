package me.rerere.rikkahub.ui.pages.workflow.codex

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.data.workflow.codex.CodexApproval
import me.rerere.rikkahub.data.workflow.codex.CodexMessageProjector
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.ai.CodexChatComposer
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.message.MessagePartsBlock
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun CodexThreadPage(
    machineId: String,
    threadId: String,
    vm: CodexThreadVM = koinViewModel(parameters = { parametersOf(machineId, threadId) }),
) {
    val navController = LocalNavController.current
    val filesManager: FilesManager = koinInject()
    val detail = vm.detail
    val hazeState = rememberHazeState()
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            val name = filesManager.getFileNameFromUri(uri) ?: "file"
            val mime = filesManager.getFileMimeType(uri) ?: "application/octet-stream"
            filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()?.let { localUri ->
                if (mime.startsWith("image/")) vm.inputState.addImages(listOf(localUri))
                else vm.inputState.addFiles(listOf(UIMessagePart.Document(localUri.toString(), name, mime)))
            }
        }
    }
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
            Column(modifier = Modifier.fillMaxWidth()) {
                vm.statusMessage?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                CodexChatComposer(
                    state = vm.inputState,
                    loading = vm.isRunning,
                    canSend = vm.canSend,
                    models = vm.runtimeCatalog?.models.orEmpty(),
                    selectedModel = vm.selectedModel,
                    selectedEffort = vm.selectedEffort,
                    fastMode = vm.fastMode,
                    permissionProfiles = vm.runtimeCatalog?.permissionProfiles.orEmpty(),
                    selectedPermission = vm.selectedPermission,
                    skills = vm.runtimeCatalog?.skills.orEmpty(),
                    plugins = vm.runtimeCatalog?.plugins.orEmpty(),
                    apps = vm.runtimeCatalog?.apps.orEmpty(),
                    capabilities = vm.runtimeCatalog?.capabilities
                        ?: me.rerere.rikkahub.data.workflow.codex.CodexCatalogCapabilities(),
                    selectedSkills = vm.selectedSkills,
                    runtimeSettings = vm.runtimeSettings,
                    hazeState = hazeState,
                    onSelectModel = vm::selectModel,
                    onSelectEffort = vm::selectEffort,
                    onFastModeChange = vm::updateFastMode,
                    onSelectPermission = vm::selectPermission,
                    onToggleSkill = vm::toggleSkill,
                    onAddAttachment = { attachmentPicker.launch(arrayOf("*/*")) },
                    onSend = vm::send,
                    onStop = vm::interrupt,
                )
            }
        },
    ) { padding ->
        val conversationBlocks = remember(detail) { CodexMessageProjector.project(detail) }
        val approvalsByItemId = remember(detail.approvals) {
            detail.approvals.mapNotNull { approval -> approval.itemId?.let { it to approval } }.toMap()
        }
        val standaloneApprovals = remember(detail.approvals) {
            detail.approvals.filter { it.itemId == null }
        }
        val listState = rememberLazyListState()
        val totalRows = conversationBlocks.size + standaloneApprovals.size
        LaunchedEffect(totalRows) {
            if (totalRows > 0) listState.animateScrollToItem(totalRows - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (conversationBlocks.isEmpty()) {
                item {
                    Text(
                        detail.thread?.preview?.ifBlank { "正在同步完整内容…" } ?: "任务不在当前缓存中",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(conversationBlocks, key = { it.stableId }) { block ->
                MessagePartsBlock(
                    assistant = null,
                    role = block.role,
                    model = null,
                    parts = block.parts,
                    annotations = emptyList(),
                    loading = block.loading,
                    onToolApproval = { toolCallId, approved, _ ->
                        approvalsByItemId[toolCallId]?.let { approval ->
                            vm.resolveApproval(approval.approvalId, if (approved) "accept" else "decline")
                        }
                    },
                    onToolAnswer = { toolCallId, answer ->
                        approvalsByItemId[toolCallId]?.let { approval ->
                            vm.resolveInteraction(approval.approvalId, answer)
                        }
                    },
                    onToolCancel = { toolCallId ->
                        approvalsByItemId[toolCallId]?.let { approval ->
                            if (approval.kind == "user_input") vm.cancelInteraction(approval.approvalId)
                            else vm.resolveApproval(approval.approvalId, "cancel")
                        }
                    },
                )
            }
            items(standaloneApprovals, key = CodexApproval::approvalId) { approval ->
                ApprovalCard(
                    approval = approval,
                    onAccept = { vm.resolveApproval(approval.approvalId, "accept") },
                    onDecline = { vm.resolveApproval(approval.approvalId, "decline") },
                    onCancel = {
                        if (approval.kind == "user_input") vm.cancelInteraction(approval.approvalId)
                        else vm.resolveApproval(approval.approvalId, "cancel")
                    },
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    approval: CodexApproval,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
) {
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
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}
