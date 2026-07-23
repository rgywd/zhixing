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
import me.rerere.rikkahub.data.work.PhoneWorkRepository
import me.rerere.rikkahub.data.work.PhoneWorkSession
import me.rerere.rikkahub.data.work.PhoneWorkSessionCreator

class PhoneWorkSessionVM(
    initialSessionId: String,
    private val repository: PhoneWorkRepository,
    private val draftStore: PhoneWorkDraftStore,
    private val sessionCreator: PhoneWorkSessionCreator,
) : ViewModel() {
    private val sessionId = MutableStateFlow(initialSessionId.takeIf { it.isNotBlank() })
    val session: StateFlow<PhoneWorkSession?> = sessionId.flatMapLatest { id ->
        id?.let(repository::observeSession) ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val events: StateFlow<List<PhoneWorkEvent>> = sessionId.flatMapLatest { id ->
        id?.let(repository::observeEvents) ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val catalog: StateFlow<PhoneWorkCatalog> = repository.catalog
    val selectedRepo = MutableStateFlow<PhoneWorkRepo?>(null)
    val selectedModel = MutableStateFlow(DEFAULT_MODELS.first())
    val selectedEffort = MutableStateFlow("high")
    val sending = MutableStateFlow(false)
    val sendError = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

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
                )
                selectedModel.value = current.model
                selectedEffort.value = current.reasoningEffort
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
                    if (sessionId.value == null) return@collect
                    error.value = null
                    runCatching { repository.refreshSessions() }
                }
        }
        viewModelScope.launch {
            chooseDefaults(catalog.value)
            while (isActive) {
                val id = sessionId.value
                val catalogResult = runCatching { repository.refreshCatalog() }
                if (id == null) {
                    catalogResult
                        .onSuccess {
                            chooseDefaults(it)
                            error.value = null
                        }
                        .onFailure { error.value = it.message ?: "无法刷新开发机目录，正在重试" }
                } else {
                    val eventResult = runCatching { repository.refreshEvents(id) }
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
        selectedRepo.value = repo
        selectedModel.value = repo.models.firstOrNull() ?: DEFAULT_MODELS.first()
        selectedEffort.value = repo.reasoningEfforts.firstOrNull { it == "high" }
            ?: repo.reasoningEfforts.firstOrNull()
            ?: "high"
    }

    fun selectModel(model: String) {
        if (sessionId.value == null) selectedModel.value = model
    }

    fun selectEffort(effort: String) {
        if (sessionId.value == null) selectedEffort.value = effort
    }

    fun send(text: String, imageUrls: List<String> = emptyList(), onAccepted: (String?) -> Unit = {}) {
        if ((text.isBlank() && imageUrls.isEmpty()) || sending.value) return
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
                        model = selectedModel.value,
                        reasoningEffort = selectedEffort.value,
                        message = text,
                        imageUrls = imageUrls,
                    ).also {
                        sessionId.value = it.id
                        onAccepted(it.id)
                    }
                } else {
                    repository.sendMessage(id, text, imageUrls)
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

    private fun chooseDefaults(value: PhoneWorkCatalog) {
        if (sessionId.value != null) return
        val available = value.repos.filter { it.available }
        val current = selectedRepo.value
        val next = available.firstOrNull { current != null && it.id == current.id && it.runnerId == current.runnerId }
            ?: available.firstOrNull()
        if (next == null) {
            selectedRepo.value = null
        } else if (next.id != current?.id || next.runnerId != current.runnerId) {
            selectRepo(next)
        } else {
            selectedRepo.value = next
            if (selectedModel.value !in next.models) selectedModel.value = next.models.firstOrNull() ?: DEFAULT_MODELS.first()
            if (selectedEffort.value !in next.reasoningEfforts) {
                selectedEffort.value = next.reasoningEfforts.firstOrNull { it == "high" }
                    ?: next.reasoningEfforts.firstOrNull()
                    ?: "high"
            }
        }
    }

    companion object {
        val DEFAULT_MODELS = listOf("gpt-5.6-sol", "gpt-5.6-terra")
        val DEFAULT_EFFORTS = listOf("medium", "high", "xhigh", "max")
    }
}
