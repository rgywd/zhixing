package me.rerere.rikkahub.ui.pages.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.work.PhoneWorkAnswer
import me.rerere.rikkahub.data.work.PhoneWorkCatalog
import me.rerere.rikkahub.data.work.PhoneWorkEvent
import me.rerere.rikkahub.data.work.PhoneWorkDraftStore
import me.rerere.rikkahub.data.work.PhoneWorkRepo
import me.rerere.rikkahub.data.work.PhoneWorkPendingAttachment
import me.rerere.rikkahub.data.work.PhoneWorkQueueItem
import me.rerere.rikkahub.data.work.PhoneWorkRepoPreferenceStore
import me.rerere.rikkahub.data.work.PhoneWorkRepository
import me.rerere.rikkahub.data.work.PhoneWorkRunnerCapabilities
import me.rerere.rikkahub.data.work.PhoneWorkRuntime
import me.rerere.rikkahub.data.work.PhoneWorkSession
import me.rerere.rikkahub.data.work.PhoneWorkSessionCreator
import me.rerere.rikkahub.data.work.chooseDefaultWorkRepo
import me.rerere.rikkahub.data.work.effectiveReasoningEfforts
import me.rerere.rikkahub.data.work.effectiveRuntimes

class PhoneWorkSessionVM(
    initialSessionId: String,
    private val repository: PhoneWorkRepository,
    private val draftStore: PhoneWorkDraftStore,
    private val sessionCreator: PhoneWorkSessionCreator,
    private val repoPreferenceStore: PhoneWorkRepoPreferenceStore,
) : ViewModel() {
    private val sessionId = MutableStateFlow(initialSessionId.takeIf { it.isNotBlank() })
    val session: StateFlow<PhoneWorkSession?> = sessionId.flatMapLatest { id ->
        id?.let(repository::observeSession) ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val events: StateFlow<List<PhoneWorkEvent>> = sessionId.flatMapLatest { id ->
        id?.let(repository::observeEvents) ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val queue: StateFlow<List<PhoneWorkQueueItem>> = sessionId.flatMapLatest { id ->
        id?.let(repository::observeQueue) ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val catalog: StateFlow<PhoneWorkCatalog> = repository.catalog
    val repoPreferences = repoPreferenceStore.state
    val selectedRepo = MutableStateFlow<PhoneWorkRepo?>(null)
    val selectedRuntime = MutableStateFlow("codex")
    val selectedModel = MutableStateFlow(DEFAULT_MODELS.first())
    val selectedEffort = MutableStateFlow("xhigh")
    val selectedFastMode = MutableStateFlow(false)
    val sending = MutableStateFlow(false)
    val sendError = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    private var initialDefaultResolved = false
    private var pendingEffortChange = false
    private var pendingFastModeChange = false

    init {
        viewModelScope.launch {
            session.filterNotNull().collect { current ->
                selectedRepo.value = PhoneWorkRepo(
                    id = current.repoId,
                    runnerId = current.runnerId,
                    name = current.repoName,
                    models = listOf(current.model),
                    reasoningEfforts = listOf(current.reasoningEffort),
                    available = true,
                    runtimes = listOf(
                        PhoneWorkRuntime(
                            id = current.runtime,
                            name = runtimeDisplayName(current.runtime),
                            models = listOf(current.model),
                            reasoningEfforts = listOf(current.reasoningEffort),
                            fastModels = listOf(current.model).takeIf { current.fastMode }.orEmpty(),
                        )
                    ),
                )
                selectedRuntime.value = current.runtime
                selectedModel.value = current.model
                val effortSelection = reconcileWorkEffortSelection(
                    selectedEffort = selectedEffort.value,
                    serverEffort = current.reasoningEffort,
                    pending = pendingEffortChange,
                )
                selectedEffort.value = effortSelection.effort
                pendingEffortChange = effortSelection.pending
                val fastSelection = reconcileWorkFastSelection(
                    selectedFastMode = selectedFastMode.value,
                    serverFastMode = current.fastMode,
                    pending = pendingFastModeChange,
                )
                selectedFastMode.value = fastSelection.fastMode
                pendingFastModeChange = fastSelection.pending
            }
        }
        viewModelScope.launch {
            sessionId.filterNotNull()
                .flatMapLatest(repository::liveEvents)
                .retryWhen { _, _ ->
                    error.value = "实时连接已断开，正在重连…"
                    delay(3_000)
                    true
                }
                .collect {
                    val id = sessionId.value ?: return@collect
                    error.value = null
                    runCatching { repository.refreshSessions() }
                    if (supportsEditableQueue(catalog.value, session.value)) {
                        runCatching { repository.refreshQueue(id) }
                    }
                }
        }
        viewModelScope.launch {
            chooseDefaults(catalog.value, confirmedCatalog = false)
            while (isActive) {
                val id = sessionId.value
                val catalogResult = runCatching { repository.refreshCatalog() }
                if (id == null) {
                    catalogResult
                        .onSuccess {
                            chooseDefaults(it, confirmedCatalog = true)
                            error.value = null
                        }
                        .onFailure { error.value = it.message ?: "无法刷新开发机目录，正在重试" }
                } else {
                    val eventResult = runCatching { repository.refreshEvents(id) }
                    if (supportsEditableQueue(catalog.value, session.value)) {
                        runCatching { repository.refreshQueue(id) }
                    }
                    error.value = when {
                        eventResult.isFailure -> eventResult.exceptionOrNull()?.message ?: "消息同步失败，正在重试"
                        catalogResult.isFailure -> "开发机状态刷新失败，消息仍会继续同步"
                        else -> null
                    }
                }
                delay(10_000)
            }
        }
    }

    fun selectRepo(repo: PhoneWorkRepo) {
        if (sessionId.value != null) return
        initialDefaultResolved = true
        applyRepo(repo, recordRecent = true)
    }

    fun toggleRepoPinned(repo: PhoneWorkRepo) {
        if (sessionId.value == null) {
            initialDefaultResolved = true
            repoPreferenceStore.togglePinned(repo)
        }
    }

    private fun applyRepo(repo: PhoneWorkRepo, recordRecent: Boolean) {
        selectedRepo.value = repo
        if (recordRecent) repoPreferenceStore.markSelected(repo)
        val runtime = repo.effectiveRuntimes().firstOrNull { it.id == "codex" }
            ?: repo.effectiveRuntimes().first()
        applyRuntime(runtime)
    }

    fun selectRuntime(runtimeId: String) {
        if (sessionId.value != null) return
        val runtime = selectedRepo.value?.effectiveRuntimes()?.firstOrNull { it.id == runtimeId } ?: return
        applyRuntime(runtime)
    }

    fun selectModel(model: String) {
        if (sessionId.value != null) return
        val runtime = selectedRepo.value?.effectiveRuntimes()
            ?.firstOrNull { it.id == selectedRuntime.value }
        if (runtime != null && model !in runtime.models) return
        selectedModel.value = model
        val efforts = runtime?.effectiveReasoningEfforts(model)
            .orEmpty()
            .ifEmpty { defaultReasoningEfforts(model) }
        if (selectedEffort.value !in efforts) {
            selectedEffort.value = preferredEffort(selectedRuntime.value, efforts)
        }
        if (model !in runtime?.fastModels.orEmpty()) selectedFastMode.value = false
    }

    fun selectEffort(effort: String) {
        val currentSession = session.value
        val efforts = currentSession?.let { workSessionReasoningEfforts(catalog.value, it) }
            ?: selectedRepo.value?.effectiveRuntimes()
                ?.firstOrNull { it.id == selectedRuntime.value }
                ?.effectiveReasoningEfforts(selectedModel.value)
                .orEmpty()
                .ifEmpty { defaultReasoningEfforts(selectedModel.value) }
        if (effort in efforts) {
            selectedEffort.value = effort
            pendingEffortChange = currentSession != null && effort != currentSession.reasoningEffort
        }
    }

    fun selectFastMode(enabled: Boolean) {
        val currentSession = session.value
        val runtime = currentSession?.let { workSessionRuntime(catalog.value, it) }
            ?: selectedRepo.value?.effectiveRuntimes()?.firstOrNull { it.id == selectedRuntime.value }
        val model = currentSession?.model ?: selectedModel.value
        if (enabled && (runtime?.id != "codex" || model !in runtime.fastModels)) return
        selectedFastMode.value = enabled
        pendingFastModeChange = currentSession != null && enabled != currentSession.fastMode
    }

    fun send(
        text: String,
        attachments: List<PhoneWorkPendingAttachment> = emptyList(),
        onAccepted: (String?) -> Unit = {},
    ) {
        if ((text.isBlank() && attachments.isEmpty()) || sending.value) return
        viewModelScope.launch {
            sending.value = true
            sendError.value = null
            val draftSessionId = sessionId.value
            runCatching {
                val id = sessionId.value
                if (id == null) {
                    val repo = selectedRepo.value ?: error("开发机还没有可用仓库")
                    sessionCreator.create(
                        repo = repo,
                        runtime = selectedRuntime.value,
                        model = selectedModel.value,
                        reasoningEffort = selectedEffort.value,
                        fastMode = selectedFastMode.value,
                        message = text,
                        attachments = attachments,
                    ).also {
                        sessionId.value = it.id
                        onAccepted(it.id)
                    }
                } else {
                    repository.sendMessage(
                        sessionId = id,
                        text = text,
                        attachments = attachments,
                        reasoningEffort = selectedEffort.value.takeIf { pendingEffortChange },
                        fastMode = selectedFastMode.value.takeIf { pendingFastModeChange },
                    )
                    repository.refreshEvents(id)
                    onAccepted(null)
                }
            }.onSuccess {
                error.value = null
                draftStore.clear(draftSessionId)
            }.onFailure {
                sendError.value = it.message ?: "发送失败"
            }
            sending.value = false
        }
    }

    fun enqueue(
        text: String,
        attachments: List<PhoneWorkPendingAttachment> = emptyList(),
        onAccepted: () -> Unit = {},
    ) {
        val id = sessionId.value ?: return
        if ((text.isBlank() && attachments.isEmpty()) || sending.value) return
        viewModelScope.launch {
            sending.value = true
            sendError.value = null
            runCatching {
                repository.enqueue(
                    sessionId = id,
                    text = text,
                    attachments = attachments,
                    reasoningEffort = selectedEffort.value.takeIf { pendingEffortChange },
                    fastMode = selectedFastMode.value.takeIf { pendingFastModeChange },
                )
            }.onSuccess {
                error.value = null
                draftStore.clear(id)
                onAccepted()
            }.onFailure {
                sendError.value = it.message ?: "加入队列失败"
            }
            sending.value = false
        }
    }

    fun steer(
        text: String,
        attachments: List<PhoneWorkPendingAttachment> = emptyList(),
        onAccepted: () -> Unit = {},
    ) {
        val current = session.value ?: return
        val turnId = current.activeTurnId ?: run {
            sendError.value = "当前 turn 已结束，请改为加入队列"
            return
        }
        if ((text.isBlank() && attachments.isEmpty()) || sending.value) return
        viewModelScope.launch {
            sending.value = true
            sendError.value = null
            runCatching {
                repository.steer(
                    sessionId = current.id,
                    expectedTurnId = turnId,
                    text = text,
                    attachments = attachments,
                )
            }.onSuccess {
                error.value = null
                draftStore.clear(current.id)
                onAccepted()
            }.onFailure {
                sendError.value = it.message ?: "引导当前任务失败"
            }
            sending.value = false
        }
    }

    fun updateQueueItem(item: PhoneWorkQueueItem, text: String, onAccepted: () -> Unit = {}) {
        val id = sessionId.value ?: return
        if ((text.isBlank() && item.attachments.isEmpty()) || sending.value || item.state != "QUEUED") return
        viewModelScope.launch {
            sending.value = true
            sendError.value = null
            runCatching { repository.updateQueueItem(id, item.id, item.revision, text.trim()) }
                .onSuccess { onAccepted() }
                .onFailure { sendError.value = it.message ?: "编辑队列失败" }
            sending.value = false
        }
    }

    fun cancelQueueItem(item: PhoneWorkQueueItem) {
        val id = sessionId.value ?: return
        if (sending.value || item.state != "QUEUED") return
        viewModelScope.launch {
            sending.value = true
            sendError.value = null
            runCatching { repository.cancelQueueItem(id, item.id, item.revision) }
                .onFailure { sendError.value = it.message ?: "撤回队列失败" }
            sending.value = false
        }
    }

    fun steerQueueItem(item: PhoneWorkQueueItem, onAccepted: () -> Unit = {}) {
        val current = session.value ?: return
        val capabilities = workRunnerCapabilities(catalog.value, current)
        if (sending.value || !canSteerQueueItem(current, capabilities, item)) return
        val turnId = current.activeTurnId ?: return
        viewModelScope.launch {
            sending.value = true
            sendError.value = null
            runCatching {
                repository.steerQueueItem(current.id, item.id, item.revision, turnId)
            }.onSuccess {
                error.value = null
                onAccepted()
            }.onFailure {
                sendError.value = it.message ?: "队列消息转为引导失败"
            }
            sending.value = false
        }
    }

    fun loadDraft(): String = draftStore.load(sessionId.value)

    fun saveDraft(text: String) {
        draftStore.save(sessionId.value, text)
    }

    fun clearSendError() {
        sendError.value = null
    }

    fun answer(askId: String, answers: List<PhoneWorkAnswer>) {
        val id = sessionId.value ?: return
        viewModelScope.launch {
            runCatching { repository.answer(id, askId, answers) }
                .onFailure { error.value = it.message ?: "提交回答失败" }
        }
    }

    fun stop() {
        val id = sessionId.value ?: return
        viewModelScope.launch {
            runCatching { repository.stop(id) }.onFailure { error.value = it.message ?: "停止失败" }
        }
    }

    suspend fun reportHtml(reportId: String): String = repository.reportHtml(reportId)

    private fun chooseDefaults(value: PhoneWorkCatalog, confirmedCatalog: Boolean) {
        if (sessionId.value != null) return
        val available = value.repos.filter { it.available }
        val current = selectedRepo.value
        val next = available.firstOrNull { current != null && it.id == current.id && it.runnerId == current.runnerId }
            ?: when {
                current != null -> chooseDefaultWorkRepo(available, repoPreferences.value)
                !initialDefaultResolved && available.isNotEmpty() -> {
                    chooseDefaultWorkRepo(available, repoPreferences.value).also {
                        if (it != null || confirmedCatalog) initialDefaultResolved = true
                    }
                }
                else -> null
            }
        if (next == null) {
            selectedRepo.value = null
        } else if (next.id != current?.id || next.runnerId != current.runnerId) {
            applyRepo(next, recordRecent = false)
        } else {
            selectedRepo.value = next
            val runtimes = next.effectiveRuntimes()
            val runtime = runtimes.firstOrNull { it.id == selectedRuntime.value }
                ?: runtimes.firstOrNull { it.id == "codex" }
                ?: runtimes.first()
            if (runtime.id != selectedRuntime.value) {
                applyRuntime(runtime)
            } else {
                if (selectedModel.value !in runtime.models) {
                    selectedModel.value = runtime.models.firstOrNull() ?: DEFAULT_MODELS.first()
                }
                val efforts = runtime.effectiveReasoningEfforts(selectedModel.value)
                if (selectedEffort.value !in efforts) {
                    selectedEffort.value = preferredEffort(runtime.id, efforts)
                }
                if (selectedModel.value !in runtime.fastModels) selectedFastMode.value = false
            }
        }
    }

    private fun applyRuntime(runtime: PhoneWorkRuntime) {
        selectedRuntime.value = runtime.id
        selectedModel.value = runtime.models.firstOrNull() ?: DEFAULT_MODELS.first()
        selectedEffort.value = preferredEffort(runtime.id, runtime.effectiveReasoningEfforts(selectedModel.value))
        selectedFastMode.value = false
    }

    companion object {
        const val SPARK_MODEL = "gpt-5.3-codex-spark"
        val DEFAULT_MODELS = listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", SPARK_MODEL)
        val DEFAULT_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
        val DEFAULT_REASONING_EFFORTS_BY_MODEL = mapOf(
            SPARK_MODEL to listOf("low", "medium", "high", "xhigh"),
        )

        fun defaultReasoningEfforts(model: String): List<String> =
            DEFAULT_REASONING_EFFORTS_BY_MODEL[model] ?: DEFAULT_EFFORTS

        fun preferredEffort(runtimeId: String, efforts: List<String>): String {
            val preferred = if (runtimeId == "claude-code") "max" else "xhigh"
            return efforts.firstOrNull { it == preferred }
                ?: efforts.firstOrNull { it == "high" }
                ?: efforts.firstOrNull()
                ?: preferred
        }
    }
}

internal fun workSessionRuntime(
    catalog: PhoneWorkCatalog,
    session: PhoneWorkSession,
): PhoneWorkRuntime? = catalog.repos
    .firstOrNull { it.runnerId == session.runnerId && it.id == session.repoId && it.available }
    ?.effectiveRuntimes()
    ?.firstOrNull { it.id == session.runtime }
    ?.takeIf { session.model in it.models }

internal fun workSessionReasoningEfforts(
    catalog: PhoneWorkCatalog,
    session: PhoneWorkSession,
): List<String> = workSessionRuntime(catalog, session)
    ?.effectiveReasoningEfforts(session.model)
    .orEmpty()
    .ifEmpty { listOf(session.reasoningEffort) }

internal fun workSessionFastAvailable(
    catalog: PhoneWorkCatalog,
    session: PhoneWorkSession,
): Boolean = session.runtime == "codex" && (
    session.fastMode || session.model in workSessionRuntime(catalog, session)?.fastModels.orEmpty()
)

internal data class WorkEffortSelection(
    val effort: String,
    val pending: Boolean,
)

internal fun reconcileWorkEffortSelection(
    selectedEffort: String,
    serverEffort: String,
    pending: Boolean,
): WorkEffortSelection = if (!pending || selectedEffort == serverEffort) {
    WorkEffortSelection(effort = serverEffort, pending = false)
} else {
    WorkEffortSelection(effort = selectedEffort, pending = true)
}

internal data class WorkFastSelection(
    val fastMode: Boolean,
    val pending: Boolean,
)

internal fun reconcileWorkFastSelection(
    selectedFastMode: Boolean,
    serverFastMode: Boolean,
    pending: Boolean,
): WorkFastSelection = if (!pending || selectedFastMode == serverFastMode) {
    WorkFastSelection(fastMode = serverFastMode, pending = false)
} else {
    WorkFastSelection(fastMode = selectedFastMode, pending = true)
}

private fun runtimeDisplayName(runtime: String): String = when (runtime) {
    "claude-code" -> "Claude Code"
    else -> "Codex"
}

internal enum class WorkInputAction {
    DIRECT,
    QUEUE,
    STEER,
    STEER_UNAVAILABLE,
}

internal fun workRunnerCapabilities(
    catalog: PhoneWorkCatalog,
    session: PhoneWorkSession?,
): PhoneWorkRunnerCapabilities = session?.runnerId
    ?.let { runnerId -> catalog.runners.firstOrNull { it.id == runnerId }?.capabilities }
    ?: PhoneWorkRunnerCapabilities()

internal fun supportsEditableQueue(catalog: PhoneWorkCatalog, session: PhoneWorkSession?): Boolean =
    workRunnerCapabilities(catalog, session).editableQueue

internal fun canSteerQueueItem(
    session: PhoneWorkSession?,
    capabilities: PhoneWorkRunnerCapabilities,
    item: PhoneWorkQueueItem,
): Boolean = item.state == "QUEUED" &&
    session?.status == "RUNNING" &&
    capabilities.appServerTurns &&
    capabilities.editableQueue &&
    capabilities.steer &&
    !session.activeTurnId.isNullOrBlank()

internal fun resolveWorkInputAction(
    session: PhoneWorkSession?,
    capabilities: PhoneWorkRunnerCapabilities,
    longPress: Boolean,
): WorkInputAction {
    if (session == null || session.status !in setOf("RUNNING", "WAITING_FOR_USER")) {
        return WorkInputAction.DIRECT
    }
    if (longPress) {
        return if (
            session.status == "RUNNING" &&
            capabilities.appServerTurns &&
            capabilities.steer &&
            !session.activeTurnId.isNullOrBlank()
        ) {
            WorkInputAction.STEER
        } else {
            WorkInputAction.STEER_UNAVAILABLE
        }
    }
    return if (capabilities.editableQueue) WorkInputAction.QUEUE else WorkInputAction.DIRECT
}
