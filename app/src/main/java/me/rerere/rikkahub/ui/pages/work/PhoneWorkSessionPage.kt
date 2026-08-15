package me.rerere.rikkahub.ui.pages.work

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.work.PhoneWorkAnswer
import me.rerere.rikkahub.data.work.PhoneWorkAskAnsweredPayload
import me.rerere.rikkahub.data.work.PhoneWorkAskPayload
import me.rerere.rikkahub.data.work.PhoneWorkAssistantMessagePayload
import me.rerere.rikkahub.data.work.PhoneWorkEvent
import me.rerere.rikkahub.data.work.PhoneWorkHtmlReportPayload
import me.rerere.rikkahub.data.work.PhoneWorkQuestion
import me.rerere.rikkahub.data.work.PhoneWorkPendingAttachment
import me.rerere.rikkahub.data.work.PhoneWorkQueueItem
import me.rerere.rikkahub.data.work.PhoneWorkRepo
import me.rerere.rikkahub.data.work.PhoneWorkRepoKey
import me.rerere.rikkahub.data.work.PhoneWorkRepoPreferences
import me.rerere.rikkahub.data.work.PhoneWorkReportPayload
import me.rerere.rikkahub.data.work.PhoneWorkRunStatePayload
import me.rerere.rikkahub.data.work.PhoneWorkSystemErrorPayload
import me.rerere.rikkahub.data.work.PhoneWorkRuntime
import me.rerere.rikkahub.data.work.PhoneWorkUserMessagePayload
import me.rerere.rikkahub.data.work.effectiveReasoningEfforts
import me.rerere.rikkahub.data.work.effectiveRuntimes
import me.rerere.rikkahub.data.work.isPinned
import me.rerere.rikkahub.data.work.isAllowedWorkAttachmentType
import me.rerere.rikkahub.data.work.key
import me.rerere.rikkahub.data.work.supportsFileAttachments
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.pages.chat.NativeChatScaffold
import me.rerere.rikkahub.ui.pages.chat.NativeChatTopBar
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private val workJson = Json { ignoreUnknownKeys = true }

@Composable
fun PhoneWorkSessionPage(sessionId: String) {
    val vm: PhoneWorkSessionVM = koinViewModel(
        key = phoneWorkSessionViewModelKey(sessionId),
        parameters = { parametersOf(sessionId) },
    )
    val navigator = LocalNavController.current
    val context = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val hazeState = rememberHazeState()
    val inputState = remember { ChatInputState() }
    val filesManager: FilesManager = koinInject()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val currentAttachments = inputState.messageContent.count {
            it is UIMessagePart.Image || it is UIMessagePart.Document
        }
        val available = (4 - currentAttachments).coerceAtLeast(0)
        if (available > 0) {
            inputState.addImages(filesManager.createChatFilesByContents(uris.take(available)))
        } else if (uris.isNotEmpty()) {
            Toast.makeText(context, "每条 Work 消息最多发送 4 个附件", Toast.LENGTH_SHORT).show()
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val currentAttachments = inputState.messageContent.count {
            it is UIMessagePart.Image || it is UIMessagePart.Document
        }
        val available = (4 - currentAttachments).coerceAtLeast(0)
        if (available == 0 && uris.isNotEmpty()) {
            Toast.makeText(context, "每条 Work 消息最多发送 4 个附件", Toast.LENGTH_SHORT).show()
        }
        val documents = uris.take(available).mapNotNull { uri ->
            val name = filesManager.getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
            val mimeType = filesManager.getFileMimeType(uri) ?: "application/octet-stream"
            if (!isAllowedWorkAttachmentType(name, mimeType)) {
                Toast.makeText(context, "Work 不支持该附件格式：$name", Toast.LENGTH_SHORT).show()
                return@mapNotNull null
            }
            val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
            if (localUri == null) {
                Toast.makeText(context, "无法读取附件：$name", Toast.LENGTH_SHORT).show()
                null
            } else {
                UIMessagePart.Document(localUri.toString(), name, mimeType)
            }
        }
        if (documents.isNotEmpty()) inputState.addFiles(documents)
    }
    val session by vm.session.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val repoPreferences by vm.repoPreferences.collectAsStateWithLifecycle()
    val selectedRepo by vm.selectedRepo.collectAsStateWithLifecycle()
    val selectedRuntime by vm.selectedRuntime.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val selectedEffort by vm.selectedEffort.collectAsStateWithLifecycle()
    val selectedFastMode by vm.selectedFastMode.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val sendError by vm.sendError.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val draft = sessionId.isBlank() && session == null
    val selectedRuntimeConfig = session?.let { workSessionRuntime(catalog, it) }
        ?: selectedRepo
            ?.effectiveRuntimes()
            ?.firstOrNull { it.id == selectedRuntime }
    val reasoningEffortOptions = session?.let { workSessionReasoningEfforts(catalog, it) }
        ?: selectedRuntimeConfig
            ?.effectiveReasoningEfforts(selectedModel)
            .orEmpty()
            .ifEmpty { PhoneWorkSessionVM.defaultReasoningEfforts(selectedModel) }
    val fastAvailable = session?.let { workSessionFastAvailable(catalog, it) }
        ?: (selectedRuntime == "codex" && selectedModel in selectedRuntimeConfig?.fastModels.orEmpty())
    val selectedRunnerId = session?.runnerId ?: selectedRepo?.runnerId
    val fileAttachmentsSupported = catalog.supportsFileAttachments(selectedRunnerId)
    val runnerCapabilities = workRunnerCapabilities(catalog, session)
    val canCompose = session?.status != "COMPLETED" && session?.archivedAt == null
    val canSubmitInput = (
        !inputState.isEmpty() || inputState.messageContent.any {
            it is UIMessagePart.Image || it is UIMessagePart.Document
        }
    ) && selectedRepo != null && (
        fileAttachmentsSupported || inputState.messageContent.none { it is UIMessagePart.Document }
    )
    var showAttachmentPicker by remember { mutableStateOf(false) }
    var messageActionTarget by remember { mutableStateOf<WorkMessageActionTarget?>(null) }
    var editingQueueItem by remember { mutableStateOf<PhoneWorkQueueItem?>(null) }
    var statusClockMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val statusPresentation = remember(session, catalog, events, statusClockMillis) {
        buildWorkSessionStatusPresentation(
            session = session,
            catalog = catalog,
            events = events,
            now = Instant.ofEpochMilli(statusClockMillis),
        )
    }

    LaunchedEffect(vm, inputState) {
        vm.loadDraft().takeIf(String::isNotBlank)?.let(inputState::setMessageText)
        snapshotFlow { inputState.textContent.text.toString() }
            .distinctUntilChanged()
            .collectLatest { text ->
                vm.saveDraft(text)
            }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000)
            statusClockMillis = System.currentTimeMillis()
        }
    }

    fun sendCurrentInput(longPress: Boolean = false) {
        val text = inputState.textContent.text.toString().trim()
        val attachments = inputState.messageContent.mapNotNull { part ->
            when (part) {
                is UIMessagePart.Image -> PhoneWorkPendingAttachment(uri = part.url)
                is UIMessagePart.Document -> PhoneWorkPendingAttachment(
                    uri = part.url,
                    fileName = part.fileName,
                    mimeType = part.mime,
                )
                else -> null
            }
        }
        if (text.isNotEmpty() || attachments.isNotEmpty()) {
            val onAccepted: (String?) -> Unit = { createdId ->
                filesManager.deleteChatFiles(attachments.map { it.uri.toUri() })
                inputState.clearInput()
                if (createdId != null) {
                    navigator.navigate(Screen.PhoneWorkSession(createdId)) {
                        popUpTo(Screen.PhoneWorkSession("")) { inclusive = true }
                    }
                }
            }
            when (resolveWorkInputAction(session, runnerCapabilities, longPress)) {
                WorkInputAction.DIRECT -> vm.send(text, attachments, onAccepted)
                WorkInputAction.QUEUE -> vm.enqueue(text, attachments) { onAccepted(null) }
                WorkInputAction.STEER -> vm.steer(text, attachments) {
                    onAccepted(null)
                    Toast.makeText(context, "已引导当前任务", Toast.LENGTH_SHORT).show()
                }
                WorkInputAction.STEER_UNAVAILABLE -> Toast.makeText(
                    context,
                    if (session?.status == "WAITING_FOR_USER") {
                        "请先回答当前问题；这条消息可点按加入队列"
                    } else {
                        "当前任务不支持实时引导，请点按加入队列"
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    NativeChatScaffold(
        topBar = {
            NativeChatTopBar(
                navigationIcon = {
                    BackButton()
                },
                title = {
                    RepoTitleSelector(
                        selected = session?.let {
                            PhoneWorkRepo(
                                id = it.repoId,
                                runnerId = it.runnerId,
                                name = it.repoName,
                                models = listOf(it.model),
                                reasoningEfforts = listOf(it.reasoningEffort),
                                available = true,
                                runtimes = listOf(
                                    PhoneWorkRuntime(
                                        id = it.runtime,
                                        name = workRuntimeDisplayName(it.runtime),
                                        models = listOf(it.model),
                                        reasoningEfforts = listOf(it.reasoningEffort),
                                    )
                                ),
                            )
                        }
                            ?: selectedRepo,
                        runtimeName = session?.runtime?.let(::workRuntimeDisplayName)
                            ?: selectedRuntimeConfig?.name
                            ?: workRuntimeDisplayName(selectedRuntime),
                        repos = catalog.repos.filter { it.available },
                        preferences = repoPreferences,
                        enabled = draft,
                        onSelect = vm::selectRepo,
                        onTogglePinned = vm::toggleRepoPinned,
                    )
                },
                actions = {
                    if (session?.status == "RUNNING" || session?.status == "WAITING_FOR_USER") {
                        IconButton(onClick = vm::stop) { Icon(HugeIcons.Cancel01, "停止本轮") }
                    }
                },
            )
        },
        bottomBar = {
            if (canCompose) {
                Column {
                    if (queue.isNotEmpty()) {
                        WorkPendingQueuePanel(
                            items = queue,
                            canSteer = session?.status == "RUNNING" &&
                                runnerCapabilities.appServerTurns &&
                                runnerCapabilities.editableQueue &&
                                runnerCapabilities.steer &&
                                !session?.activeTurnId.isNullOrBlank(),
                            onSteer = { item ->
                                vm.steerQueueItem(item) {
                                    Toast.makeText(context, "已转为引导当前任务", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onEdit = { editingQueueItem = it },
                            onCancel = vm::cancelQueueItem,
                        )
                    }
                    Text(
                        if (draft) {
                            "运行引擎、仓库和模型在会话创建后固定；思考深度与速度后续仍可调整"
                        } else if (session?.status == "WAITING_FOR_USER" && runnerCapabilities.editableQueue) {
                            "请先回答当前问题；点按发送可加入队列"
                        } else if (session?.status == "RUNNING" && runnerCapabilities.editableQueue && runnerCapabilities.steer) {
                            "点按加入队列 · 长按引导当前任务"
                        } else if (session?.runtime == "codex") {
                            "思考深度与速度调整将在下一轮生效"
                        } else {
                            "思考深度调整将在下一轮生效"
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (
                        session?.status == "RUNNING" &&
                        runnerCapabilities.appServerTurns &&
                        runnerCapabilities.steer &&
                        !session?.activeTurnId.isNullOrBlank()
                    ) {
                        TextButton(
                            onClick = { sendCurrentInput(longPress = true) },
                            enabled = canSubmitInput && !sending,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text("引导当前任务")
                        }
                    }
                    sendError?.let { message ->
                        WorkSendErrorBar(
                            message = message,
                            onRetry = { sendCurrentInput() },
                            onDismiss = vm::clearSendError,
                        )
                    }
                    ChatInput(
                        state = inputState,
                        loading = sending,
                        settings = settings,
                        hazeState = hazeState,
                        enableSearch = false,
                        onToggleSearch = {},
                        onUpdateChatModel = {},
                        onUpdateAssistant = {},
                        onUpdateSearchService = { _, _ -> },
                        onMoreClick = { showAttachmentPicker = true },
                        onCancelClick = {},
                        onSendClick = { sendCurrentInput() },
                        onLongSendClick = { sendCurrentInput(longPress = true) },
                        canSend = canSubmitInput,
                        showMoreButton = true,
                        customLeadingControls = {
                            WorkChoiceButton(
                                label = selectedRuntimeConfig?.name ?: workRuntimeDisplayName(selectedRuntime),
                                options = selectedRepo?.effectiveRuntimes()?.map { it.id }.orEmpty(),
                                enabled = draft,
                                icon = { Icon(HugeIcons.AiMagic, null, Modifier.size(18.dp)) },
                                optionLabel = { runtimeId ->
                                    selectedRepo?.effectiveRuntimes()?.firstOrNull { it.id == runtimeId }?.name
                                        ?: workRuntimeDisplayName(runtimeId)
                                },
                                onSelect = vm::selectRuntime,
                            )
                            WorkModelSettingsButton(
                                model = selectedModel,
                                models = selectedRuntimeConfig?.models.orEmpty().ifEmpty {
                                    PhoneWorkSessionVM.DEFAULT_MODELS
                                },
                                modelEnabled = draft,
                                reasoningEffort = selectedEffort,
                                reasoningEfforts = reasoningEffortOptions,
                                codex = selectedRuntime == "codex",
                                fastMode = selectedFastMode,
                                fastAvailable = fastAvailable,
                                onSelectModel = vm::selectModel,
                                onSelectReasoningEffort = vm::selectEffort,
                                onSelectFastMode = vm::selectFastMode,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        WorkEventList(
            events = events,
            contentPadding = padding,
            error = error,
            statusPresentation = statusPresentation,
            onAnswer = vm::answer,
            onMessageActions = { text, user ->
                messageActionTarget = WorkMessageActionTarget(text = text, user = user)
            },
            onOpenReport = { reportId ->
                scope.launch {
                    runCatching { vm.reportHtml(reportId) }
                        .onSuccess { html ->
                            val contentId = WebViewContentCache.store(context.cacheDir, html)
                            val title = events.asSequence()
                                .mapNotNull { event -> runCatching { workJson.decodeFromJsonElement<PhoneWorkHtmlReportPayload>(event.payload) }.getOrNull() }
                                .firstOrNull { it.reportId == reportId }
                                ?.title
                                ?: "Work 报告"
                            navigator.navigate(Screen.PhoneWorkReport(contentId = contentId, title = title))
                        }
                }
            },
        )
    }

    messageActionTarget?.let { target ->
        WorkMessageActionsSheet(
            target = target,
            canCompose = canCompose,
            onDismiss = { messageActionTarget = null },
            onQuote = { text ->
                inputState.setMessageText(mergeWorkQuote(inputState.textContent.text.toString(), text))
                messageActionTarget = null
            },
            onEditAndResend = { text ->
                inputState.clearInput()
                inputState.setMessageText(text)
                messageActionTarget = null
            },
        )
    }

    editingQueueItem?.let { item ->
        WorkQueueEditSheet(
            item = item,
            saving = sending,
            onDismiss = { editingQueueItem = null },
            onSave = { text ->
                vm.updateQueueItem(item, text) { editingQueueItem = null }
            },
        )
    }

    if (showAttachmentPicker) {
        ModalBottomSheet(onDismissRequest = { showAttachmentPicker = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        showAttachmentPicker = false
                        imagePicker.launch("image/*")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.Image02, contentDescription = null)
                    Text("选择图片", modifier = Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(
                    onClick = {
                        showAttachmentPicker = false
                        filePicker.launch(arrayOf("*/*"))
                    },
                    enabled = fileAttachmentsSupported,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.Files02, contentDescription = null)
                    Text("选择文件", modifier = Modifier.padding(start = 8.dp))
                }
                if (!fileAttachmentsSupported) {
                    Text(
                        "当前开发机 Runner 尚未支持普通文件；更新 Runner 后会自动启用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

internal fun phoneWorkSessionViewModelKey(sessionId: String): String =
    "phone-work-session:${sessionId.ifBlank { "new" }}"

private fun formatWorkReportSize(size: Long): String? = when {
    size <= 0 -> null
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> "%.1f MB".format(size / 1024.0 / 1024.0)
}

@Composable
private fun RepoTitleSelector(
    selected: PhoneWorkRepo?,
    runtimeName: String,
    repos: List<PhoneWorkRepo>,
    preferences: PhoneWorkRepoPreferences,
    enabled: Boolean,
    onSelect: (PhoneWorkRepo) -> Unit,
    onTogglePinned: (PhoneWorkRepo) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box {
        Column(
            modifier = Modifier.clickable(enabled = enabled) { expanded = true },
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(selected?.name ?: "选择仓库", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (selected == null) {
                    "等待开发机目录"
                } else {
                    selected.group?.let { "$it · $runtimeName 完全访问" } ?: "$runtimeName · 完全访问"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (expanded) {
        val sections = remember(repos, query, preferences) {
            buildWorkRepoSections(repos, query, preferences)
        }
        ModalBottomSheet(
            onDismissRequest = {
                expanded = false
                query = ""
            },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("选择工作目录", style = MaterialTheme.typography.titleLarge)
                Text(
                    "目录由开发机 Runner 从已授权位置发现",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索工作目录") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sections.isEmpty()) {
                    Text(
                        if (query.isBlank()) "开发机暂时没有可用目录" else "没有匹配“$query”的目录",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        sections.forEach { section ->
                            item(key = "section:${section.id}") {
                                Text(
                                    section.title,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            items(section.repos, key = { repo -> "${repo.runnerId}:${repo.id}" }) { repo ->
                                val isSelected = selected != null && repo.id == selected.id && repo.runnerId == selected.runnerId
                                val isPinned = preferences.isPinned(repo)
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    onSelect(repo)
                                                    expanded = false
                                                    query = ""
                                                }
                                                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(HugeIcons.Folder01, null, modifier = Modifier.size(24.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(repo.name, style = MaterialTheme.typography.bodyLarge)
                                                Text(
                                                    repo.group?.takeIf(String::isNotBlank) ?: "固定目录",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (isSelected) {
                                                Text(
                                                    "已选择",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        IconButton(onClick = { onTogglePinned(repo) }) {
                                            Icon(
                                                if (isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                                                if (isPinned) "取消置顶 ${repo.name}" else "置顶 ${repo.name}",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal data class WorkRepoSection(
    val id: String,
    val title: String,
    val repos: List<PhoneWorkRepo>,
)

internal fun buildWorkRepoSections(
    repos: List<PhoneWorkRepo>,
    query: String,
    preferences: PhoneWorkRepoPreferences,
): List<WorkRepoSection> {
    val normalizedQuery = query.trim()
    val candidates = repos.asSequence()
        .filter { it.available }
        .filter { repo ->
            normalizedQuery.isBlank() || repo.name.contains(normalizedQuery, ignoreCase = true) ||
                repo.group.orEmpty().contains(normalizedQuery, ignoreCase = true)
        }
        .toList()
    val candidatesByKey = candidates.associateBy(PhoneWorkRepo::key)
    fun preferredRepos(keys: List<PhoneWorkRepoKey>) =
        keys.mapNotNull(candidatesByKey::get).distinctBy(PhoneWorkRepo::key)

    val pinned = preferredRepos(preferences.pinned)
    val pinnedKeys = pinned.mapTo(mutableSetOf(), PhoneWorkRepo::key)
    val recent = preferredRepos(preferences.recent).filterNot { it.key() in pinnedKeys }
    val preferredKeys = (pinned + recent).mapTo(mutableSetOf(), PhoneWorkRepo::key)
    val remainingGroups = candidates.asSequence()
        .filterNot { it.key() in preferredKeys }
        .sortedWith(compareBy<PhoneWorkRepo>({ it.group.orEmpty() }, { it.name }))
        .groupBy { it.group?.takeIf(String::isNotBlank) ?: "固定目录" }
    return buildList {
        if (pinned.isNotEmpty()) add(WorkRepoSection("pinned", "置顶", pinned))
        if (recent.isNotEmpty()) add(WorkRepoSection("recent", "最近使用", recent))
        remainingGroups.forEach { (group, items) ->
            add(WorkRepoSection("group:$group", group, items))
        }
    }
}

@Composable
private fun WorkChoiceButton(
    label: String,
    options: List<String>,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    optionLabel: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { if (enabled) expanded = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.distinct().forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

private enum class WorkModelSettingsPane {
    ROOT,
    MODEL,
    REASONING,
    SPEED,
}

@Composable
private fun WorkModelSettingsButton(
    model: String,
    models: List<String>,
    modelEnabled: Boolean,
    reasoningEffort: String,
    reasoningEfforts: List<String>,
    codex: Boolean,
    fastMode: Boolean,
    fastAvailable: Boolean,
    onSelectModel: (String) -> Unit,
    onSelectReasoningEffort: (String) -> Unit,
    onSelectFastMode: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pane by remember { mutableStateOf(WorkModelSettingsPane.ROOT) }
    fun dismiss() {
        expanded = false
        pane = WorkModelSettingsPane.ROOT
    }
    val speedLabel = if (fastMode) "快速" else "标准"
    Box {
        Surface(
            onClick = { expanded = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HugeIcons.AiMagic, null, Modifier.size(18.dp))
                Text(
                    buildString {
                        append(model)
                        append(" · ")
                        append(reasoningEffort)
                        if (codex) {
                            append(" · ")
                            append(speedLabel)
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = ::dismiss) {
            when (pane) {
                WorkModelSettingsPane.ROOT -> {
                    WorkSettingsRootItem(
                        title = "模型名称",
                        value = model,
                        enabled = modelEnabled,
                        onClick = { pane = WorkModelSettingsPane.MODEL },
                    )
                    WorkSettingsRootItem(
                        title = "思考深度",
                        value = reasoningEffort,
                        onClick = { pane = WorkModelSettingsPane.REASONING },
                    )
                    if (codex) {
                        WorkSettingsRootItem(
                            title = "速度",
                            value = speedLabel,
                            onClick = { pane = WorkModelSettingsPane.SPEED },
                        )
                    }
                }
                WorkModelSettingsPane.MODEL -> WorkSettingsOptions(
                    title = "模型名称",
                    options = models.distinct(),
                    selected = model,
                    onBack = { pane = WorkModelSettingsPane.ROOT },
                    onSelect = { onSelectModel(it); dismiss() },
                )
                WorkModelSettingsPane.REASONING -> WorkSettingsOptions(
                    title = "思考深度",
                    options = reasoningEfforts.distinct(),
                    selected = reasoningEffort,
                    onBack = { pane = WorkModelSettingsPane.ROOT },
                    onSelect = { onSelectReasoningEffort(it); dismiss() },
                )
                WorkModelSettingsPane.SPEED -> {
                    WorkSettingsBackItem("速度") { pane = WorkModelSettingsPane.ROOT }
                    DropdownMenuItem(
                        text = { Text("标准") },
                        trailingIcon = { if (!fastMode) Text("✓", color = MaterialTheme.colorScheme.primary) },
                        onClick = { onSelectFastMode(false); dismiss() },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("快速")
                                if (!fastAvailable) {
                                    Text(
                                        "当前模型不支持",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        enabled = fastAvailable,
                        trailingIcon = { if (fastMode) Text("✓", color = MaterialTheme.colorScheme.primary) },
                        onClick = { onSelectFastMode(true); dismiss() },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkSettingsRootItem(
    title: String,
    value: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title)
                Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        enabled = enabled,
        trailingIcon = { Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun WorkSettingsOptions(
    title: String,
    options: List<String>,
    selected: String,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    WorkSettingsBackItem(title, onBack)
    options.forEach { option ->
        DropdownMenuItem(
            text = { Text(option) },
            trailingIcon = { if (option == selected) Text("✓", color = MaterialTheme.colorScheme.primary) },
            onClick = { onSelect(option) },
        )
    }
}

@Composable
private fun WorkSettingsBackItem(title: String, onBack: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = { Text("‹", style = MaterialTheme.typography.titleLarge) },
        onClick = onBack,
    )
    HorizontalDivider()
}

private fun workRuntimeDisplayName(runtime: String): String = when (runtime) {
    "claude-code" -> "Claude Code"
    else -> "Codex"
}

@Composable
private fun WorkEventList(
    events: List<PhoneWorkEvent>,
    contentPadding: PaddingValues,
    error: String?,
    statusPresentation: WorkSessionStatusPresentation?,
    onAnswer: (String, List<PhoneWorkAnswer>) -> Unit,
    onMessageActions: (String, Boolean) -> Unit,
    onOpenReport: (String) -> Unit,
) {
    val timelineItems = remember(events) { buildWorkTimeline(events) }
    val answeredAskSources = remember(events) {
        events.filter { it.type == "ASK_ANSWERED" }.mapNotNull { event ->
            runCatching { workJson.decodeFromJsonElement<PhoneWorkAskAnsweredPayload>(event.payload) }.getOrNull()
                ?.let { it.askId to it.source }
        }.toMap()
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var hasPositionedInitialContent by remember { mutableStateOf(false) }
    var previousItemCount by remember { mutableIntStateOf(0) }
    var unseenCount by remember { mutableIntStateOf(0) }
    val itemCount = timelineItems.size + if (error != null) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount == 0) {
            hasPositionedInitialContent = false
            previousItemCount = 0
            unseenCount = 0
        } else if (hasPositionedInitialContent) {
            val decision = decideWorkScroll(
                previousItemCount = previousItemCount,
                newItemCount = itemCount,
                lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
            )
            if (decision.followLatest) {
                listState.animateScrollToItem(itemCount - 1)
                unseenCount = 0
            } else {
                unseenCount += decision.addedItems
            }
            previousItemCount = itemCount
        } else {
            // Opening a long session must show the latest progress immediately. Animating from
            // index 0 exposes stale history first and can be cancelled by incoming events.
            listState.scrollToItem(itemCount - 1)
            hasPositionedInitialContent = true
            previousItemCount = itemCount
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.totalItemsCount > 0 && layout.visibleItemsInfo.lastOrNull()?.index == layout.totalItemsCount - 1
        }.distinctUntilChanged().collect { atBottom ->
            if (atBottom) unseenCount = 0
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            statusPresentation?.let { WorkSessionStatusBar(it) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let {
                    item {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
                items(timelineItems, key = { it.itemKey }) { item ->
                    when (item) {
                        is WorkTimelineItem.DayHeader -> Text(
                            item.label,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        is WorkTimelineItem.Entry -> WorkEventEntry(
                            event = item.event,
                            answeredAskSources = answeredAskSources,
                            onAnswer = onAnswer,
                            onMessageActions = onMessageActions,
                            onOpenReport = onOpenReport,
                        )
                    }
                }
            }
        }
        val showJumpToLatest = unseenCount > 0 || (
            listState.layoutInfo.totalItemsCount > 0 &&
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index != listState.layoutInfo.totalItemsCount - 1
            )
        if (showJumpToLatest) {
            FilledTonalButton(
                onClick = {
                    scope.launch { listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0)) }
                    unseenCount = 0
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp),
            ) {
                Icon(HugeIcons.ArrowDown01, null, modifier = Modifier.size(18.dp))
                Text(if (unseenCount > 0) "$unseenCount 条新消息" else "跳到最新")
            }
        }
    }
}

@Composable
private fun WorkSessionStatusBar(presentation: WorkSessionStatusPresentation) {
    val containerColor by animateColorAsState(
        targetValue = when (presentation.kind) {
            WorkSessionStatusKind.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
            WorkSessionStatusKind.WAITING -> MaterialTheme.colorScheme.tertiaryContainer
            WorkSessionStatusKind.IDLE -> MaterialTheme.colorScheme.secondaryContainer
            WorkSessionStatusKind.TERMINAL -> MaterialTheme.colorScheme.surfaceContainer
            WorkSessionStatusKind.FAILURE,
            WorkSessionStatusKind.ATTENTION,
            -> MaterialTheme.colorScheme.errorContainer
        },
        label = "work-status-bar-container",
    )
    val contentColor by animateColorAsState(
        targetValue = when (presentation.kind) {
            WorkSessionStatusKind.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
            WorkSessionStatusKind.WAITING -> MaterialTheme.colorScheme.onTertiaryContainer
            WorkSessionStatusKind.IDLE -> MaterialTheme.colorScheme.onSecondaryContainer
            WorkSessionStatusKind.TERMINAL -> MaterialTheme.colorScheme.onSurfaceVariant
            WorkSessionStatusKind.FAILURE,
            WorkSessionStatusKind.ATTENTION,
            -> MaterialTheme.colorScheme.onErrorContainer
        },
        label = "work-status-bar-content",
    )
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkPulsingDot(
                color = contentColor,
                pulse = presentation.kind == WorkSessionStatusKind.ACTIVE,
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(presentation.headline, style = MaterialTheme.typography.labelLarge)
                Text(
                    presentation.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.78f),
                )
            }
        }
    }
}

@Composable
private fun WorkRunStateTimelineMarker(label: String, status: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(label, color = workStatusColor(status), style = MaterialTheme.typography.labelSmall)
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

internal sealed interface WorkTimelineItem {
    val itemKey: String

    data class DayHeader(val label: String, override val itemKey: String) : WorkTimelineItem

    data class Entry(val event: PhoneWorkEvent) : WorkTimelineItem {
        override val itemKey: String get() = event.id
    }
}

internal fun workDayLabel(
    createdAt: String,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String? {
    val instant = runCatching { Instant.parse(createdAt) }.getOrNull() ?: return null
    val date = instant.atZone(zoneId).toLocalDate()
    val today = now.atZone(zoneId).toLocalDate()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

internal fun buildWorkTimeline(
    events: List<PhoneWorkEvent>,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<WorkTimelineItem> {
    val visible = events.filterIndexed { index, event ->
        val previous = events.getOrNull(index - 1)
        event.type != "RUN_STATE" || previous?.type != "RUN_STATE" || previous.payload != event.payload
    }
    val items = mutableListOf<WorkTimelineItem>()
    var lastDayLabel: String? = null
    visible.forEach { event ->
        val dayLabel = workDayLabel(event.createdAt, now, zoneId)
        if (dayLabel != null && dayLabel != lastDayLabel) {
            items += WorkTimelineItem.DayHeader(dayLabel, "day:${event.id}")
            lastDayLabel = dayLabel
        }
        items += WorkTimelineItem.Entry(event)
    }
    return items
}

@Composable
private fun WorkEventEntry(
    event: PhoneWorkEvent,
    answeredAskSources: Map<String, String?>,
    onAnswer: (String, List<PhoneWorkAnswer>) -> Unit,
    onMessageActions: (String, Boolean) -> Unit,
    onOpenReport: (String) -> Unit,
) {
    when (event.type) {
        "USER_MESSAGE" -> {
            val message = workJson.decodeFromJsonElement<PhoneWorkUserMessagePayload>(event.payload)
            WorkUserMessageBubble(message, onLongClick = {
                message.text.takeIf(String::isNotBlank)?.let { onMessageActions(it, true) }
            })
        }
        "REPORT" -> {
            val text = workJson.decodeFromJsonElement<PhoneWorkReportPayload>(event.payload).text
            WorkReportBubble(text, onLongClick = { onMessageActions(text, false) })
        }
        "ASSISTANT_MESSAGE" -> {
            val text = workJson.decodeFromJsonElement<PhoneWorkAssistantMessagePayload>(event.payload).text
            WorkAssistantMessage(text, onLongClick = { onMessageActions(text, false) })
        }
        "ASK" -> {
            val ask = workJson.decodeFromJsonElement<PhoneWorkAskPayload>(event.payload)
            PhoneWorkAskCard(ask, answeredAskSources[ask.askId], answeredAskSources.containsKey(ask.askId), onAnswer)
        }
        "HTML_REPORT" -> {
            val report = workJson.decodeFromJsonElement<PhoneWorkHtmlReportPayload>(event.payload)
            WorkHtmlReportCard(
                report = report,
                createdAt = event.createdAt,
                onClick = { onOpenReport(report.reportId) },
            )
        }
        "RUN_STATE" -> {
            val state = workJson.decodeFromJsonElement<PhoneWorkRunStatePayload>(event.payload)
            WorkRunStateTimelineMarker(
                label = workRunStateTimelineLabel(state.status, state.detail, event.createdAt),
                status = state.status,
            )
        }
        "SYSTEM_ERROR" -> {
            val failure = workJson.decodeFromJsonElement<PhoneWorkSystemErrorPayload>(event.payload)
            WorkSystemErrorCard(failure.message)
        }
    }
}

@Composable
private fun WorkSystemErrorCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun WorkAssistantMessage(text: String, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    SelectionContainer {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .padding(horizontal = 20.dp, vertical = 2.dp),
        ) {
            MarkdownBlock(text)
        }
    }
}

@Composable
private fun WorkReportBubble(text: String, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "汇报",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                SelectionContainer {
                    MarkdownBlock(text)
                }
            }
        }
    }
}

@Composable
private fun WorkHtmlReportCard(
    report: PhoneWorkHtmlReportPayload,
    createdAt: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    HugeIcons.Book03,
                    null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    report.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        formatWorkReportSize(report.size),
                        formatWorkCardTimestamp(createdAt),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                HugeIcons.ArrowRight01,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkUserMessageBubble(message: PhoneWorkUserMessagePayload, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                ),
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    message.attachments.forEach { attachment ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(HugeIcons.Book03, null, modifier = Modifier.size(18.dp))
                            Text(
                                attachment.fileName,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (message.text.isNotBlank()) Text(message.text)
                }
            }
        }
    }
}

internal fun workQueueItemPreview(item: PhoneWorkQueueItem): String = item.text
    .trim()
    .lineSequence()
    .firstOrNull(String::isNotBlank)
    ?.take(80)
    ?: if (item.attachments.isNotEmpty()) "${item.attachments.size} 个附件" else "空消息"

@Composable
private fun WorkPendingQueuePanel(
    items: List<PhoneWorkQueueItem>,
    canSteer: Boolean,
    onSteer: (PhoneWorkQueueItem) -> Unit,
    onEdit: (PhoneWorkQueueItem) -> Unit,
    onCancel: (PhoneWorkQueueItem) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val first = items.first()
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "待执行队列 · ${items.size} 项",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "队首：${workQueueItemPreview(first)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                    if (expanded) "折叠待执行队列" else "展开待执行队列",
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                workQueueItemPreview(item),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val details = buildList {
                                if (item.attachments.isNotEmpty()) add("${item.attachments.size} 个附件")
                                if (item.state == "DISPATCHING") add("正在派发")
                            }.joinToString(" · ")
                            if (details.isNotBlank()) {
                                Text(
                                    details,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(
                            onClick = { onSteer(item) },
                            enabled = canSteer && item.state == "QUEUED",
                        ) {
                            Icon(HugeIcons.Forward02, "将第 ${index + 1} 条队列消息转为引导当前任务")
                        }
                        IconButton(
                            onClick = { onEdit(item) },
                            enabled = item.state == "QUEUED",
                        ) {
                            Icon(HugeIcons.PencilEdit01, "编辑第 ${index + 1} 条队列消息")
                        }
                        IconButton(
                            onClick = { onCancel(item) },
                            enabled = item.state == "QUEUED",
                        ) {
                            Icon(HugeIcons.Delete01, "撤回第 ${index + 1} 条队列消息")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkQueueEditSheet(
    item: PhoneWorkQueueItem,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(item.id, item.revision) { mutableStateOf(item.text) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("编辑队列消息", style = MaterialTheme.typography.titleLarge)
            Text(
                "只修改这条待执行消息，不会覆盖输入框中的草稿。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text("消息内容") },
            )
            if (item.attachments.isNotEmpty()) {
                Text(
                    "保留 ${item.attachments.size} 个附件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                Button(
                    onClick = { onSave(text.trim()) },
                    enabled = (text.isNotBlank() || item.attachments.isNotEmpty()) && !saving,
                ) { Text(if (saving) "保存中…" else "保存") }
            }
        }
    }
}

@Composable
private fun WorkSendErrorBar(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "发送失败：$message",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("重试") }
            IconButton(onClick = onDismiss) { Icon(HugeIcons.Cancel01, "关闭") }
        }
    }
}

private data class WorkMessageActionTarget(val text: String, val user: Boolean)

@Composable
private fun WorkMessageActionsSheet(
    target: WorkMessageActionTarget,
    canCompose: Boolean,
    onDismiss: () -> Unit,
    onQuote: (String) -> Unit,
    onEditAndResend: (String) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("消息操作", style = MaterialTheme.typography.titleLarge)
            SelectionContainer {
                Text(
                    target.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Work message", target.text)))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("复制全文") }
            if (canCompose) {
                TextButton(onClick = { onQuote(target.text) }, modifier = Modifier.fillMaxWidth()) {
                    Text("引用回复")
                }
                if (target.user) {
                    TextButton(onClick = { onEditAndResend(target.text) }, modifier = Modifier.fillMaxWidth()) {
                        Text("编辑后发送")
                    }
                }
            }
        }
    }
}

internal data class WorkScrollDecision(val followLatest: Boolean, val addedItems: Int)

internal fun decideWorkScroll(previousItemCount: Int, newItemCount: Int, lastVisibleIndex: Int): WorkScrollDecision {
    val addedItems = (newItemCount - previousItemCount).coerceAtLeast(0)
    val wasAtBottom = previousItemCount == 0 || lastVisibleIndex >= previousItemCount - 1
    return WorkScrollDecision(followLatest = wasAtBottom, addedItems = if (wasAtBottom) 0 else addedItems)
}

internal fun quoteForWorkInput(text: String): String = text.lines().joinToString("\n") { line -> "> $line" }

internal fun mergeWorkQuote(current: String, quoted: String): String = buildString {
    current.trimEnd().takeIf(String::isNotBlank)?.let {
        append(it)
        append("\n\n")
    }
    append(quoteForWorkInput(quoted))
    append("\n\n")
}

@Composable
private fun PhoneWorkAskCard(
    ask: PhoneWorkAskPayload,
    answeredSource: String?,
    answered: Boolean,
    onAnswer: (String, List<PhoneWorkAnswer>) -> Unit,
) {
    var selections by remember(ask.askId) {
        mutableStateOf(ask.questions.associate { it.id to it.recommendedOptionIds.toSet() })
    }
    var other by remember(ask.askId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(
                        if (answered) {
                            MaterialTheme.colorScheme.outlineVariant
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    ),
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (answered) {
                    Text(
                        if (answeredSource == "timeout_default") "已超时，采用推荐方案" else "已回答",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else {
                    Text(
                        "需要你回答",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    AskDeadlineCountdown(deadlineAt = ask.deadlineAt)
                    ask.questions.forEach { question ->
                        QuestionEditor(
                            question = question,
                            selected = selections[question.id].orEmpty(),
                            otherText = other[question.id].orEmpty(),
                            onSelected = { selections = selections + (question.id to it) },
                            onOther = { other = other + (question.id to it) },
                        )
                    }
                    val valid = ask.questions.all { selections[it.id].orEmpty().isNotEmpty() || other[it.id].orEmpty().isNotBlank() }
                    Button(
                        onClick = {
                            onAnswer(
                                ask.askId,
                                ask.questions.map { question ->
                                    PhoneWorkAnswer(
                                        questionId = question.id,
                                        selectedOptionIds = selections[question.id].orEmpty().toList(),
                                        otherText = other[question.id]?.takeIf(String::isNotBlank),
                                    )
                                },
                            )
                        },
                        enabled = valid,
                    ) { Text("提交回答") }
                }
            }
        }
    }
}

@Composable
private fun AskDeadlineCountdown(deadlineAt: String?) {
    val deadlineMillis = remember(deadlineAt) {
        deadlineAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    } ?: return
    var remainingSeconds by remember(deadlineMillis) {
        mutableLongStateOf((deadlineMillis - System.currentTimeMillis()) / 1000)
    }
    LaunchedEffect(deadlineMillis) {
        while (isActive) {
            remainingSeconds = (deadlineMillis - System.currentTimeMillis()) / 1000
            if (remainingSeconds <= 0) break
            delay(1_000)
        }
    }
    Text(
        if (remainingSeconds > 0) {
            "%d:%02d 后自动采用推荐方案".format(remainingSeconds / 60, remainingSeconds % 60)
        } else {
            "正在采用推荐方案…"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun QuestionEditor(
    question: PhoneWorkQuestion,
    selected: Set<String>,
    otherText: String,
    onSelected: (Set<String>) -> Unit,
    onOther: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question.header, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(question.question, style = MaterialTheme.typography.titleMedium)
        question.options.forEach { option ->
            val active = option.id in selected
            val recommended = option.id in question.recommendedOptionIds
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    onSelected(
                        if (question.multiSelect) {
                            if (active) selected - option.id else selected + option.id
                        } else setOf(option.id)
                    )
                },
                color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option.label)
                        if (recommended) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    "推荐",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                    option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        OutlinedTextField(
            value = otherText,
            onValueChange = onOther,
            label = { Text("其他") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
