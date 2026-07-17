package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.workflow.RepoPreset
import me.rerere.rikkahub.data.workflow.WorkMachine
import me.rerere.rikkahub.data.workflow.WorkRepository
import me.rerere.rikkahub.data.workflow.WorkSession
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyAuthException

class WorkflowVM(
    private val repository: WorkRepository,
) : ViewModel() {
    var recoveryKey by mutableStateOf("")
        private set
    var relayUrl by mutableStateOf(repository.relayServerUrl.value)
        private set
    var relayUrlError by mutableStateOf<String?>(null)
        private set
    var isSavingRelayUrl by mutableStateOf(false)
        private set
    var isSecretVisible by mutableStateOf(false)
        private set
    var status by mutableStateOf<WorkflowConnectionStatus>(
        if (repository.connected.value) WorkflowConnectionStatus.Connected
        else WorkflowConnectionStatus.Disconnected
    )
        private set
    var machines by mutableStateOf<List<WorkMachine>>(emptyList())
        private set
    var sessions by mutableStateOf<List<WorkSession>>(emptyList())
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set
    var presets by mutableStateOf<List<RepoPreset>>(emptyList())
        private set
    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<WorkSession>>(emptyList())
        private set
    private var searchJob: Job? = null

    val projects by derivedStateOf { buildWorkflowProjects(sessions, machines) }
    val relayUrlChanged by derivedStateOf { relayUrl.trim().trimEnd('/') != repository.relayServerUrl.value }

    init {
        viewModelScope.launch { repository.observeMachines().collect { machines = it } }
        viewModelScope.launch { repository.observeSessions().collect { sessions = it } }
        viewModelScope.launch { repository.observePresets().collect { presets = it } }
        viewModelScope.launch { repository.syncing.collect { isRefreshing = it } }
        viewModelScope.launch {
            repository.syncError.collect { syncError = it?.toWorkflowMessage() }
        }
        viewModelScope.launch {
            repository.connected.collect { connected ->
                status = when {
                    connected -> WorkflowConnectionStatus.Connected
                    status is WorkflowConnectionStatus.Connecting ||
                        status is WorkflowConnectionStatus.Error -> status
                    else -> WorkflowConnectionStatus.Disconnected
                }
            }
        }
        if (repository.connected.value) {
            viewModelScope.launch {
                repository.ensureRealtime()
                runCatching { repository.refreshSnapshot() }
            }
        }
        // Socket 增量信号驱动快照刷新；conflate + delay 避免生成期间的事件风暴
        viewModelScope.launch {
            repository.updates.conflate().collect {
                runCatching { repository.refreshSnapshot() }
                delay(SNAPSHOT_THROTTLE_MS)
            }
        }
    }

    fun updateRecoveryKey(value: String) {
        recoveryKey = value
        if (status is WorkflowConnectionStatus.Error) {
            status = WorkflowConnectionStatus.Disconnected
        }
    }

    fun updateRelayUrl(value: String) {
        relayUrl = value
        relayUrlError = null
    }

    fun saveRelayUrl() {
        if (!relayUrlChanged || isSavingRelayUrl) return
        isSavingRelayUrl = true
        viewModelScope.launch {
            try {
                relayUrl = repository.updateRelayServerUrl(relayUrl)
                relayUrlError = null
            } catch (exception: IllegalArgumentException) {
                relayUrlError = exception.message ?: "中继地址格式不正确"
            } catch (_: Exception) {
                relayUrlError = "无法保存中继地址"
            } finally {
                isSavingRelayUrl = false
            }
        }
    }

    fun toggleSecretVisibility() {
        isSecretVisible = !isSecretVisible
    }

    fun connect() {
        val key = recoveryKey.trim()
        if (key.isBlank() || status == WorkflowConnectionStatus.Connecting) return

        status = WorkflowConnectionStatus.Connecting
        viewModelScope.launch {
            status = try {
                repository.connect(key)
                recoveryKey = ""
                isSecretVisible = false
                WorkflowConnectionStatus.Connected
            } catch (_: IllegalArgumentException) {
                WorkflowConnectionStatus.Error("恢复密钥格式不正确，请检查后重试")
            } catch (exception: HappyAuthException) {
                val message = when (exception.statusCode) {
                    401, 403 -> "Happy 拒绝了此密钥，请确认密钥属于当前账户"
                    else -> "Happy 登录失败（${exception.statusCode}），请稍后重试"
                }
                WorkflowConnectionStatus.Error(message)
            } catch (_: Exception) {
                WorkflowConnectionStatus.Error("无法连接 Happy 服务，请检查网络后重试")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
            recoveryKey = ""
            isSecretVisible = false
            syncError = null
            status = WorkflowConnectionStatus.Disconnected
        }
    }

    fun refresh() {
        viewModelScope.launch {
            repository.ensureRealtime()
            runCatching { repository.refreshSnapshot() }
        }
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            searchResults = runCatching { repository.searchSessions(query) }.getOrDefault(emptyList())
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchQuery = ""
        searchResults = emptyList()
    }

    private companion object {
        const val SNAPSHOT_THROTTLE_MS = 2_000L
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}

sealed interface WorkflowConnectionStatus {
    data object Disconnected : WorkflowConnectionStatus
    data object Connecting : WorkflowConnectionStatus
    data object Connected : WorkflowConnectionStatus
    data class Error(val message: String) : WorkflowConnectionStatus
}
