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
import me.rerere.rikkahub.data.workflow.codex.CodexCatalogRepository
import me.rerere.rikkahub.data.workflow.codex.CodexProject
import me.rerere.rikkahub.data.workflow.codex.CodexThread
import me.rerere.rikkahub.data.workflow.codex.CodexThreadDetail
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

    val needsAttention by derivedStateOf {
        projects.flatMap(CodexProject::threads)
            .filter { !it.isSubagent && it.runtimeState.needsAttention }
            .sortedByDescending(CodexThread::recencyAt)
    }

    init {
        viewModelScope.launch { repository.observeProjects().collect { projects = it } }
        if (isConnected) refresh()
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

    private companion object {
        const val DEFAULT_RELAY_URL = "https://relay.8-208-118-119.sslip.io"
    }
}

class CodexThreadVM(
    machineId: String,
    threadId: String,
    repository: CodexCatalogRepository,
) : ViewModel() {
    var detail by mutableStateOf(CodexThreadDetail(null, emptyList()))
        private set

    init {
        viewModelScope.launch {
            repository.observeThreadDetail(machineId, threadId).collect { detail = it }
        }
    }
}
