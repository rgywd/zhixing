package me.rerere.rikkahub.ui.pages.work

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
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
    val error by vm.error.collectAsStateWithLifecycle()
    val draft = sessionId.isBlank() && session == null

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
            if (session?.status != "COMPLETED") {
                ChatInput(
                    state = inputState,
                    loading = sending,
                    settings = settings,
                    hazeState = hazeState,
                    enableSearch = false,
                    onToggleSearch = {},
                    onUpdateChatModel = {},
                    onUpdateAssistant = {},
                    onUpdateSearchService = {},
                    onMoreClick = { imagePicker.launch("image/*") },
                    onCancelClick = {},
                    onSendClick = {
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
                    },
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
        },
    ) { padding ->
        WorkEventList(
            events = events,
            contentPadding = padding,
            error = error,
            onAnswer = vm::answer,
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
    LaunchedEffect(visibleEvents.size) {
        val lastIndex = visibleEvents.size + if (error != null) 1 else 0
        if (lastIndex > 0) listState.animateScrollToItem(lastIndex - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) } }
        items(visibleEvents, key = { it.id }) { event ->
            when (event.type) {
                "USER_MESSAGE" -> WorkUserMessageBubble(
                    workJson.decodeFromJsonElement<PhoneWorkUserMessagePayload>(event.payload),
                )
                "REPORT" -> WorkMarkdownBubble(
                    workJson.decodeFromJsonElement<PhoneWorkReportPayload>(event.payload).text,
                    user = false,
                )
                "ASSISTANT_MESSAGE" -> WorkMarkdownBubble(
                    workJson.decodeFromJsonElement<PhoneWorkAssistantMessagePayload>(event.payload).text,
                    user = false,
                )
                "ASK" -> {
                    val ask = workJson.decodeFromJsonElement<PhoneWorkAskPayload>(event.payload)
                    PhoneWorkAskCard(ask, ask.askId in answeredAskIds, onAnswer)
                }
                "HTML_REPORT" -> {
                    val report = workJson.decodeFromJsonElement<PhoneWorkHtmlReportPayload>(event.payload)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onOpenReport(report.reportId) },
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
}

@Composable
private fun WorkMarkdownBubble(text: String, user: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(if (user) 0.84f else 1f),
        ) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (user) Text(text) else MarkdownBlock(text)
            }
        }
    }
}

@Composable
private fun WorkUserMessageBubble(message: PhoneWorkUserMessagePayload) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(0.84f),
        ) {
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
