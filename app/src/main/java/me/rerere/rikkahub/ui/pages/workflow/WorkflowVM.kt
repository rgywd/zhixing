package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyAuthApi
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyAuthException
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyCredentialsStore
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyCredentials
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyMachine
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySession
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySyncApi
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySyncException
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySocketClient

class WorkflowVM(
    private val authApi: HappyAuthApi,
    private val credentialsStore: HappyCredentialsStore,
    private val syncApi: HappySyncApi,
    private val socketClient: HappySocketClient,
) : ViewModel() {
    private val initialCredentials = credentialsStore.load()

    var recoveryKey by mutableStateOf("")
        private set
    var isSecretVisible by mutableStateOf(false)
        private set
    var status by mutableStateOf<WorkflowConnectionStatus>(
        if (initialCredentials == null) {
            WorkflowConnectionStatus.Disconnected
        } else {
            WorkflowConnectionStatus.Connected
        }
    )
        private set
    var machines by mutableStateOf<List<HappyMachine>>(emptyList())
        private set
    var sessions by mutableStateOf<List<HappySession>>(emptyList())
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set

    init {
        initialCredentials?.let(::refresh)
    }

    fun updateRecoveryKey(value: String) {
        recoveryKey = value
        if (status is WorkflowConnectionStatus.Error) {
            status = WorkflowConnectionStatus.Disconnected
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
                val credentials = authApi.exchangeRecoveryKey(key)
                credentialsStore.save(credentials)
                recoveryKey = ""
                isSecretVisible = false
                refreshSnapshot(credentials)
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
        socketClient.disconnect()
        credentialsStore.clear()
        recoveryKey = ""
        isSecretVisible = false
        machines = emptyList()
        sessions = emptyList()
        syncError = null
        status = WorkflowConnectionStatus.Disconnected
    }

    fun refresh() {
        val credentials = credentialsStore.load() ?: run {
            status = WorkflowConnectionStatus.Disconnected
            return
        }
        refresh(credentials)
    }

    private fun refresh(credentials: HappyCredentials) {
        if (isRefreshing) return
        viewModelScope.launch {
            refreshSnapshot(credentials)
        }
    }

    private suspend fun refreshSnapshot(
        credentials: HappyCredentials,
    ) {
        isRefreshing = true
        syncError = null
        try {
            val snapshot = syncApi.fetchSnapshot(credentials)
            machines = snapshot.machines
            sessions = snapshot.sessions
        } catch (exception: HappySyncException) {
            syncError = if (exception.statusCode == 401 || exception.statusCode == 403) {
                "Happy 登录已失效，请断开后重新连接"
            } else {
                "同步失败（${exception.statusCode}），请稍后重试"
            }
        } catch (_: Exception) {
            syncError = "暂时无法同步机器和会话，请检查网络"
        } finally {
            isRefreshing = false
        }
    }
}

sealed interface WorkflowConnectionStatus {
    data object Disconnected : WorkflowConnectionStatus
    data object Connecting : WorkflowConnectionStatus
    data object Connected : WorkflowConnectionStatus
    data class Error(val message: String) : WorkflowConnectionStatus
}
