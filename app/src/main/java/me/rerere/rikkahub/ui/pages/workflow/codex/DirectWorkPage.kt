package me.rerere.rikkahub.ui.pages.workflow.codex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.FlowPreview
import androidx.compose.runtime.snapshotFlow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.work.WorkUiStore
import me.rerere.rikkahub.data.work.WorkAppMode
import me.rerere.rikkahub.data.workflow.codex.CodexApproval
import me.rerere.rikkahub.data.workflow.codex.CodexMessageProjector
import me.rerere.rikkahub.ui.components.ai.CodexChatComposer
import me.rerere.rikkahub.ui.components.message.MessagePartsBlock
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.chat.NativeChatScaffold
import me.rerere.rikkahub.ui.pages.chat.NativeChatTimeline
import me.rerere.rikkahub.ui.pages.chat.NativeChatTopBar
import me.rerere.rikkahub.ui.pages.chat.WorkDrawerContent
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun DirectWorkRoute(store: WorkUiStore = koinInject()) {
    val state by store.state.collectAsStateWithLifecycle()
    LaunchedEffect(store) { store.setMode(WorkAppMode.WORK) }
    val active = state.activeRepository
    if (active == null) {
        DirectWorkEmptyPage()
    } else {
        DirectWorkPage(
            repositoryId = active.id,
            key = active.id,
        )
    }
}

@Composable
private fun DirectWorkPage(
    repositoryId: String,
    key: String,
    vm: DirectWorkVM = koinViewModel(key = key, parameters = { parametersOf(repositoryId) }),
) {
    val navController = LocalNavController.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val size = currentWindowDpSize()
    val bigScreen = size.width > size.height && size.width >= 1100.dp

    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    val content: @Composable () -> Unit = {
        DirectWorkContent(vm = vm, bigScreen = bigScreen, onOpenDrawer = { scope.launch { drawerState.open() } })
    }
    if (bigScreen) {
        PermanentNavigationDrawer(drawerContent = { WorkDrawerContent(navController) }) { content() }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                WorkDrawerContent(navController) { scope.launch { drawerState.close() } }
            },
        ) { content() }
    }
}

@Composable
@OptIn(FlowPreview::class)
private fun DirectWorkContent(
    vm: DirectWorkVM,
    bigScreen: Boolean,
    onOpenDrawer: () -> Unit,
    workUiStore: WorkUiStore = koinInject(),
) {
    val hazeState = rememberHazeState()
    val detail = vm.detail
    val blocks = remember(detail) { CodexMessageProjector.project(detail) }
    val approvalsByItemId = remember(detail.approvals) {
        detail.approvals.mapNotNull { approval -> approval.itemId?.let { it to approval } }.toMap()
    }
    val projectedItemIds = remember(detail.turns) {
        detail.turns.flatMap { turn -> turn.items.map { it.itemId } }.toSet()
    }
    val standaloneApprovals = remember(detail.approvals, projectedItemIds) {
        detail.approvals.filter { approval -> approval.itemId !in projectedItemIds }
    }
    val listState = rememberLazyListState()
    var showOptions by remember { mutableStateOf(false) }
    val workState by workUiStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm) {
        vm.activate()
        snapshotFlow { vm.inputState.textContent.text.toString() }
            .debounce(350)
            .distinctUntilChanged()
            .collect(vm::persistDraft)
    }
    LaunchedEffect(blocks.size, standaloneApprovals.size) {
        val size = blocks.size + standaloneApprovals.size
        if (size > 0) listState.animateScrollToItem(size - 1)
    }

    NativeChatScaffold(
            topBar = {
                NativeChatTopBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    navigationIcon = {
                        if (!bigScreen) IconButton(onClick = onOpenDrawer) { Icon(HugeIcons.Menu03, "打开侧栏") }
                    },
                    title = {
                        Column {
                            Text(
                                vm.detail.thread?.name?.ifBlank { null } ?: vm.repository?.displayName ?: "Work",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Codex / ${vm.selectedModel?.displayName ?: "模型"}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::newThread) {
                            Icon(HugeIcons.MessageAdd01, "新建 Work 对话")
                        }
                    },
                )
            },
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    (vm.inputRestriction ?: vm.statusMessage)?.let { message ->
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (vm.connected) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                            if (!vm.connected) TextButton(onClick = vm::connect) { Text("重连") }
                        }
                    }
                    CodexChatComposer(
                        state = vm.inputState,
                        loading = vm.isRunning || vm.sending,
                        canSend = vm.canSend,
                        models = vm.runtimeCatalog.models,
                        selectedModel = vm.selectedModel,
                        selectedEffort = vm.selectedEffort,
                        skills = vm.runtimeCatalog.skills,
                        plugins = vm.runtimeCatalog.plugins,
                        apps = vm.runtimeCatalog.apps,
                        runtimeSettings = vm.runtimeSettings,
                        hazeState = hazeState,
                        onSelectModel = vm::selectModel,
                        onSelectEffort = vm::selectEffort,
                        onAddAttachment = { showOptions = true },
                        onSend = vm::send,
                        onStop = vm::interrupt,
                    )
                }
            },
        ) { padding ->
            NativeChatTimeline(
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
                if (!vm.connected && blocks.isEmpty() && standaloneApprovals.isEmpty()) {
                    item {
                        Text(
                            vm.statusMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(blocks, key = { it.stableId }) { block ->
                    MessagePartsBlock(
                        assistant = null,
                        role = block.role,
                        model = null,
                        parts = block.parts,
                        annotations = emptyList(),
                        loading = block.loading,
                        onToolApproval = { toolCallId, approved, _ ->
                            approvalsByItemId[toolCallId]?.let { vm.resolveApproval(it.approvalId, if (approved) "accept" else "decline") }
                        },
                        onToolAnswer = { toolCallId, answer ->
                            approvalsByItemId[toolCallId]?.let { vm.resolveInteraction(it.approvalId, answer) }
                        },
                        onToolCancel = { toolCallId ->
                            approvalsByItemId[toolCallId]?.let {
                                if (it.kind == "user_input") vm.cancelInteraction(it.approvalId)
                                else vm.resolveApproval(it.approvalId, "cancel")
                            }
                        },
                    )
                }
                items(standaloneApprovals, key = CodexApproval::approvalId) { approval ->
                    if (approval.kind == "user_input") {
                        MessagePartsBlock(
                            assistant = null,
                            role = MessageRole.ASSISTANT,
                            model = null,
                            parts = listOf(
                                UIMessagePart.Tool(
                                    toolCallId = "request-${approval.approvalId}",
                                    toolName = "ask_user",
                                    input = approval.payload.toString(),
                                    approvalState = ToolApprovalState.Pending,
                                )
                            ),
                            annotations = emptyList(),
                            loading = true,
                            onToolApproval = { _, _, _ -> },
                            onToolAnswer = { _, answer -> vm.resolveInteraction(approval.approvalId, answer) },
                            onToolCancel = { vm.cancelInteraction(approval.approvalId) },
                        )
                    } else Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("需要你的确认", style = MaterialTheme.typography.titleSmall)
                            Text(approval.summary)
                            TextButton(onClick = { vm.resolveApproval(approval.approvalId, "accept") }) { Text("允许一次") }
                            TextButton(onClick = { vm.resolveApproval(approval.approvalId, "decline") }) { Text("拒绝") }
                            TextButton(onClick = { vm.resolveApproval(approval.approvalId, "cancel") }) { Text("取消") }
                        }
                    }
                }
            }
    }
    if (showOptions) {
        CodexWorkOptionsSheet(
            state = vm.inputState,
            fastMode = vm.fastMode,
            fastSupported = vm.selectedModel?.serviceTiers?.any { it.id == "priority" } == true,
            permissionProfiles = vm.runtimeCatalog.permissionProfiles,
            selectedPermission = vm.selectedPermission,
            repositories = workState.repositories,
            selectedRepositoryId = workState.activeRepositoryId,
            onFastModeChange = vm::updateFastMode,
            onPermissionChange = vm::selectPermission,
            onRepositoryChange = { repositoryId ->
                scope.launch {
                    workUiStore.selectRepository(repositoryId)
                    showOptions = false
                }
            },
            onCompact = vm::compactThread,
            onDismiss = { showOptions = false },
        )
    }
}

@Composable
private fun DirectWorkEmptyPage() {
    val navController = LocalNavController.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            WorkDrawerContent(navController) { scope.launch { drawerState.close() } }
        },
    ) {
        NativeChatScaffold(
            topBar = {
                NativeChatTopBar(
                    navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(HugeIcons.Menu03, "打开侧栏") } },
                    title = { Text("Work") },
                )
            },
            bottomBar = {},
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("还没有 Work 仓库", style = MaterialTheme.typography.headlineSmall)
                Text("从现有设置中的 Work 卡片添加一个仓库。不会自动导入历史 Codex 项目。")
                TextButton(onClick = { navController.navigate(me.rerere.rikkahub.Screen.Setting) }) { Text("前往设置") }
            }
        }
    }
}
