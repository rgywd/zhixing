package me.rerere.rikkahub.ui.pages.workflow.codex

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.workflow.codex.CodexCatalogRepository
import me.rerere.rikkahub.data.workflow.codex.CodexProject
import me.rerere.rikkahub.data.workflow.codex.CodexThread
import me.rerere.rikkahub.data.workflow.codex.CodexThreadDetail
import me.rerere.rikkahub.data.workflow.codex.CodexRuntimeState
import me.rerere.rikkahub.data.workflow.codex.RuntimeCommandPayload
import me.rerere.rikkahub.data.workflow.codex.needsAttention
import me.rerere.rikkahub.data.workflow.wire.WireRelayClient

class CodexWorkflowVM(
    private val repository: CodexCatalogRepository,
    private val relayClient: WireRelayClient,
) : ViewModel() {
    var projects by mutableStateOf<List<CodexProject>>(emptyList())
        private set
    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<CodexThread>>(emptyList())
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var isConnected by mutableStateOf(relayClient.connected)
        private set
    var recoveryKey by mutableStateOf("")
        private set
    var relayUrl by mutableStateOf(DEFAULT_RELAY_URL)
        private set
    private var searchJob: Job? = null
    var isStartingTask by mutableStateOf(false)
        private set
    var newThreadTarget by mutableStateOf<Pair<String, String>?>(null)
        private set
    private var startTaskRequestId: String? = null

    val needsAttention by derivedStateOf {
        projects.flatMap(CodexProject::threads)
            .filter { !it.isSubagent && it.runtimeState.needsAttention }
            .sortedByDescending(CodexThread::recencyAt)
    }

    init {
        viewModelScope.launch { repository.observeProjects().collect { projects = it } }
        if (isConnected) refresh()
        viewModelScope.launch {
            while (true) {
                delay(1_500)
                if (relayClient.connected && !isRefreshing) runCatching { relayClient.sync(200) }
                startTaskRequestId?.let { requestId ->
                    projects.map(CodexProject::machineId).distinct().forEach { machineId ->
                        relayClient.consumeCommandResult(
                            machineId,
                            null,
                            "thread.start",
                            requestId,
                        )?.let { result ->
                            startTaskRequestId = null
                            isStartingTask = false
                            if (!result.ok) {
                                statusMessage = result.error ?: "新建任务失败"
                            } else {
                                val threadId = result.result?.get("threadId")?.jsonPrimitive?.contentOrNull
                                if (threadId != null) newThreadTarget = machineId to threadId
                            }
                        }
                    }
                }
            }
        }
    }

    fun project(projectId: String): CodexProject? = projects.firstOrNull { it.projectId == projectId }

    fun refresh() {
        if (isRefreshing) return
        if (!relayClient.connected) {
            isConnected = false
            statusMessage = "开发环境尚未连接；已缓存的项目仍可离线查看"
            return
        }
        viewModelScope.launch {
            isRefreshing = true
            statusMessage = null
            runCatching { relayClient.sync(200) }
                .onSuccess { result ->
                    statusMessage = if (result.gapDetected) "同步序列不连续，请稍后重试" else null
                }
                .onFailure { statusMessage = "暂时无法同步；当前展示本机缓存" }
            isConnected = relayClient.connected
            isRefreshing = false
        }
    }

    fun updateSearchQuery(value: String) {
        searchQuery = value
        searchJob?.cancel()
        if (value.isBlank()) {
            searchResults = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            searchResults = repository.search(value)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchQuery = ""
        searchResults = emptyList()
    }

    fun updateRecoveryKey(value: String) {
        recoveryKey = value
        statusMessage = null
    }

    fun updateRelayUrl(value: String) {
        relayUrl = value
        statusMessage = null
    }

    fun connect() {
        if (recoveryKey.isBlank() || isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            statusMessage = null
            runCatching { relayClient.connect(recoveryKey, relayUrl) }
                .onSuccess {
                    recoveryKey = ""
                    isConnected = true
                    statusMessage = "已连接，正在同步 Codex 项目"
                }
                .onFailure { statusMessage = it.message ?: "无法连接开发环境" }
            isRefreshing = false
            if (isConnected) refresh()
        }
    }

    fun disconnect() {
        relayClient.disconnect()
        isConnected = false
        recoveryKey = ""
        statusMessage = "已断开；本机缓存未删除"
    }

    fun startTask(project: CodexProject, text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || isStartingTask || !relayClient.connected) return
        viewModelScope.launch {
            isStartingTask = true
            statusMessage = null
            runCatching {
                startTaskRequestId = relayClient.sendRuntimeCommand(
                    RuntimeCommandPayload(
                        command = "thread.start",
                        machineId = project.machineId,
                        cwd = project.canonicalRoot,
                        text = prompt,
                    )
                )
            }.onFailure {
                isStartingTask = false
                statusMessage = it.message ?: "无法创建任务"
            }
        }
    }

    fun consumeNewThreadTarget() {
        newThreadTarget = null
    }

    private companion object {
        const val DEFAULT_RELAY_URL = "https://relay.8-208-118-119.sslip.io"
    }
}

class CodexThreadVM(
    private val machineId: String,
    private val threadId: String,
    repository: CodexCatalogRepository,
    private val relayClient: WireRelayClient,
) : ViewModel() {
    var detail by mutableStateOf(CodexThreadDetail(null, emptyList()))
        private set
    var draft by mutableStateOf("")
        private set
    var isSending by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var showTakeoverConfirmation by mutableStateOf(false)
        private set
    var deleted by mutableStateOf(false)
        private set
    private val pendingRequests = mutableMapOf<String, String>()
    private val pendingDrafts = mutableMapOf<String, String>()

    val canSend: Boolean get() = relayClient.connected && draft.isNotBlank() && !isSending
    val isRunning: Boolean get() = detail.thread?.runtimeState == CodexRuntimeState.RUNNING

    init {
        viewModelScope.launch {
            repository.observeThreadDetail(machineId, threadId).collect { detail = it }
        }
        viewModelScope.launch {
            if (relayClient.connected) {
                runCatching {
                    val requestId = relayClient.sendRuntimeCommand(
                        RuntimeCommandPayload("thread.detail", machineId, threadId)
                    )
                    pendingRequests[requestId] = "thread.detail"
                }
            }
            while (true) {
                if (relayClient.connected) {
                    runCatching { relayClient.sync(200) }
                    for ((requestId, command) in pendingRequests.toMap()) {
                        relayClient.consumeCommandResult(machineId, threadId, command, requestId)?.let { result ->
                            pendingRequests.remove(requestId)
                            val pendingDraft = pendingDrafts.remove(requestId)
                            if (!result.ok) {
                                statusMessage = result.error ?: "操作失败"
                            } else {
                                if (pendingDraft != null && draft == pendingDraft) draft = ""
                                if (command == "turn.steer") statusMessage = "补充要求已发送"
                                if (command == "thread.delete") deleted = true
                            }
                            isSending = false
                        }
                    }
                }
                delay(1_500)
            }
        }
    }

    fun updateDraft(value: String) {
        draft = value
        statusMessage = null
    }

    fun send() {
        if (!canSend) return
        if (detail.thread?.runtimeState == CodexRuntimeState.UNKNOWN) {
            showTakeoverConfirmation = true
            return
        }
        sendConfirmed(false)
    }

    fun confirmTakeover() {
        showTakeoverConfirmation = false
        sendConfirmed(true)
    }

    fun dismissTakeover() {
        showTakeoverConfirmation = false
    }

    private fun sendConfirmed(confirmedUnknown: Boolean) {
        val text = draft.trim()
        if (text.isEmpty() || isSending) return
        viewModelScope.launch {
            isSending = true
            statusMessage = null
            val command = if (isRunning) "turn.steer" else "turn.start"
            runCatching {
                val requestId = relayClient.sendRuntimeCommand(
                    RuntimeCommandPayload(
                        command = command,
                        machineId = machineId,
                        threadId = threadId,
                        text = text,
                        confirmedUnknown = confirmedUnknown,
                    )
                )
                pendingRequests[requestId] = command
                pendingDrafts[requestId] = text
            }.onFailure {
                statusMessage = it.message ?: "无法发送"
                isSending = false
            }
        }
    }

    fun interrupt() = command("turn.interrupt")
    fun archive() = command("thread.archive")
    fun unarchive() = command("thread.unarchive")
    fun delete() = command("thread.delete")

    fun resolveApproval(approvalId: String, decision: String) = command(
        "approval.resolve",
        approvalId = approvalId,
        decision = decision,
    )

    private fun command(command: String, approvalId: String? = null, decision: String? = null) {
        if (!relayClient.connected || isSending) return
        viewModelScope.launch {
            isSending = true
            statusMessage = null
            runCatching {
                val requestId = relayClient.sendRuntimeCommand(
                    RuntimeCommandPayload(
                        command = command,
                        machineId = machineId,
                        threadId = threadId,
                        approvalId = approvalId,
                        decision = decision,
                    )
                )
                pendingRequests[requestId] = command
            }.onFailure {
                statusMessage = it.message ?: "操作失败"
                isSending = false
            }
        }
    }
}
