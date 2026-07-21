package me.rerere.rikkahub.ui.pages.work

import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.work.PhoneWorkAnswer
import me.rerere.rikkahub.data.work.PhoneWorkAskPayload
import me.rerere.rikkahub.data.work.PhoneWorkAssistantMessagePayload
import me.rerere.rikkahub.data.work.PhoneWorkEvent
import me.rerere.rikkahub.data.work.PhoneWorkHtmlReportPayload
import me.rerere.rikkahub.data.work.PhoneWorkQuestion
import me.rerere.rikkahub.data.work.PhoneWorkRepo
import me.rerere.rikkahub.data.work.PhoneWorkReportPayload
import me.rerere.rikkahub.data.work.PhoneWorkRunStatePayload
import me.rerere.rikkahub.data.work.PhoneWorkUserMessagePayload
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
    val vm: PhoneWorkSessionVM = koinViewModel(parameters = { parametersOf(sessionId) })
    val navigator = LocalNavController.current
    val context = LocalContext.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val hazeState = rememberHazeState()
    val inputState = remember { ChatInputState() }
    val filesManager: FilesManager = koinInject()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val currentImages = inputState.messageContent.filterIsInstance<UIMessagePart.Image>().size
        val available = (4 - currentImages).coerceAtLeast(0)
        if (available > 0) {
            inputState.addImages(filesManager.createChatFilesByContents(uris.take(available)))
        }
    }
    val session by vm.session.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val selectedRepo by vm.selectedRepo.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val selectedEffort by vm.selectedEffort.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val sendError by vm.sendError.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val draft = sessionId.isBlank() && session == null
    val canCompose = session?.status != "COMPLETED" && session?.archivedAt == null
    var messageActionTarget by remember { mutableStateOf<WorkMessageActionTarget?>(null) }

    LaunchedEffect(vm, inputState) {
        vm.loadDraft().takeIf(String::isNotBlank)?.let(inputState::setMessageText)
        snapshotFlow { inputState.textContent.text.toString() }
            .distinctUntilChanged()
            .collectLatest { text ->
                vm.saveDraft(text)
            }
    }

    fun sendCurrentInput() {
        val text = inputState.textContent.text.toString().trim()
        val imageUrls = inputState.messageContent
            .filterIsInstance<UIMessagePart.Image>()
            .map { it.url }
        if (text.isNotEmpty() || imageUrls.isNotEmpty()) {
            vm.send(text, imageUrls) { createdId ->
                inputState.clearInput()
                if (createdId != null) {
                    navigator.navigate(Screen.PhoneWorkSession(createdId)) {
                        popUpTo(Screen.PhoneWorkSession("")) { inclusive = true }
                    }
                }
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
                        selected = session?.let { PhoneWorkRepo(it.repoId, it.runnerId, it.repoName, listOf(it.model), listOf(it.reasoningEffort), true) }
                            ?: selectedRepo,
                        repos = catalog.repos.filter { it.available },
                        enabled = draft,
                        onSelect = vm::selectRepo,
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
                    sendError?.let { message ->
                        WorkSendErrorBar(
                            message = message,
                            onRetry = ::sendCurrentInput,
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
                        onMoreClick = { imagePicker.launch("image/*") },
                        onCancelClick = {},
                        onSendClick = ::sendCurrentInput,
                        onLongSendClick = {},
                        canSend = (!inputState.isEmpty() || inputState.messageContent.any { it is UIMessagePart.Image }) &&
                            selectedRepo != null,
                        showMoreButton = true,
                        customLeadingControls = {
                            WorkChoiceButton(
                                label = selectedModel,
                                options = selectedRepo?.models.orEmpty().ifEmpty { PhoneWorkSessionVM.DEFAULT_MODELS },
                                enabled = draft,
                                icon = { Icon(HugeIcons.AiMagic, null, Modifier.size(18.dp)) },
                                onSelect = vm::selectModel,
                            )
                            WorkChoiceButton(
                                label = selectedEffort,
                                options = selectedRepo?.reasoningEfforts.orEmpty().ifEmpty { PhoneWorkSessionVM.DEFAULT_EFFORTS },
                                enabled = draft,
                                icon = { Text("A", style = MaterialTheme.typography.labelLarge) },
                                onSelect = vm::selectEffort,
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
}

@Composable
private fun RepoTitleSelector(
    selected: PhoneWorkRepo?,
    repos: List<PhoneWorkRepo>,
    enabled: Boolean,
    onSelect: (PhoneWorkRepo) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier.clickable(enabled = enabled) { expanded = true },
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(selected?.name ?: "选择仓库", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (selected == null) "等待开发机目录" else "Codex · 完全访问",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            repos.forEach { repo ->
                DropdownMenuItem(
                    text = { Text(repo.name) },
                    leadingIcon = { Icon(HugeIcons.Folder01, null) },
                    onClick = { onSelect(repo); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun WorkChoiceButton(
    label: String,
    options: List<String>,
    enabled: Boolean,
    icon: @Composable () -> Unit,
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
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
private fun WorkEventList(
    events: List<PhoneWorkEvent>,
    contentPadding: PaddingValues,
    error: String?,
    onAnswer: (String, List<PhoneWorkAnswer>) -> Unit,
    onMessageActions: (String, Boolean) -> Unit,
    onOpenReport: (String) -> Unit,
) {
    val visibleEvents = remember(events) {
        events.filterIndexed { index, event ->
            val previous = events.getOrNull(index - 1)
            event.type != "RUN_STATE" || previous?.type != "RUN_STATE" || previous.payload != event.payload
        }
    }
    val answeredAskIds = remember(visibleEvents) {
        visibleEvents.filter { it.type == "ASK_ANSWERED" }.mapNotNull { it.payload.jsonObject["askId"]?.jsonPrimitive?.content }.toSet()
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var hasPositionedInitialContent by remember { mutableStateOf(false) }
    var previousItemCount by remember { mutableIntStateOf(0) }
    var unseenCount by remember { mutableIntStateOf(0) }
    val itemCount = visibleEvents.size + if (error != null) 1 else 0
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
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            items(visibleEvents, key = { it.id }) { event ->
                when (event.type) {
                    "USER_MESSAGE" -> {
                        val message = workJson.decodeFromJsonElement<PhoneWorkUserMessagePayload>(event.payload)
                        WorkUserMessageBubble(message, onLongClick = {
                            message.text.takeIf(String::isNotBlank)?.let { onMessageActions(it, true) }
                        })
                    }
                    "REPORT" -> {
                        val text = workJson.decodeFromJsonElement<PhoneWorkReportPayload>(event.payload).text
                        WorkMarkdownBubble(text, user = false, onLongClick = { onMessageActions(text, false) })
                    }
                    "ASSISTANT_MESSAGE" -> {
                        val text = workJson.decodeFromJsonElement<PhoneWorkAssistantMessagePayload>(event.payload).text
                        WorkMarkdownBubble(text, user = false, onLongClick = { onMessageActions(text, false) })
                    }
                    "ASK" -> {
                        val ask = workJson.decodeFromJsonElement<PhoneWorkAskPayload>(event.payload)
                        PhoneWorkAskCard(ask, ask.askId in answeredAskIds, onAnswer)
                    }
                    "HTML_REPORT" -> {
                        val report = workJson.decodeFromJsonElement<PhoneWorkHtmlReportPayload>(event.payload)
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                .clickable { onOpenReport(report.reportId) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(HugeIcons.Book03, null)
                                Column {
                                    Text(report.title, style = MaterialTheme.typography.titleMedium)
                                    Text("打开完整报告", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    "RUN_STATE" -> {
                        val state = workJson.decodeFromJsonElement<PhoneWorkRunStatePayload>(event.payload)
                        Text(
                            "${state.status.displayStatus()}${state.detail?.let { " · $it" }.orEmpty()}",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
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
private fun WorkMarkdownBubble(text: String, user: Boolean, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth(if (user) 0.84f else 1f)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                ),
        ) {
            SelectionContainer {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (user) Text(text) else MarkdownBlock(text)
                }
            }
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
    answered: Boolean,
    onAnswer: (String, List<PhoneWorkAnswer>) -> Unit,
) {
    var selections by remember(ask.askId) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var other by remember(ask.askId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (answered) {
                Text("已回答", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            } else {
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
                    Text(option.label)
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
