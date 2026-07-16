package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
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
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySpawnResult

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
    var isCreatingSession by mutableStateOf(false)
        private set
    var createError by mutableStateOf<String?>(null)
        private set
    var pendingDirectoryApproval by mutableStateOf<PendingDirectoryApproval?>(null)
        private set
    var createdSessionId by mutableStateOf<String?>(null)
        private set

    val projects: List<WorkflowProject> get() = buildWorkflowProjects(sessions, machines)

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

    fun createCodexSession(machineId: String, path: String, prompt: String) {
        spawnCodexSession(machineId, path, prompt, approvedNewDirectoryCreation = false)
    }

    fun approveDirectoryCreation() {
        val pending = pendingDirectoryApproval ?: return
        pendingDirectoryApproval = null
        spawnCodexSession(
            machineId = pending.machineId,
            path = pending.path,
            prompt = pending.prompt,
            approvedNewDirectoryCreation = true,
        )
    }

    fun dismissDirectoryApproval() {
        pendingDirectoryApproval = null
    }

    fun consumeCreatedSession() {
        createdSessionId = null
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

    private fun spawnCodexSession(
        machineId: String,
        path: String,
        prompt: String,
        approvedNewDirectoryCreation: Boolean,
    ) {
        val credentials = credentialsStore.load() ?: run {
            createError = "Happy 登录已失效，请到设置中重新连接"
            return
        }
        val machine = machines.firstOrNull { it.id == machineId }
        if (machine == null || !machine.active) {
            createError = "所选开发机当前不在线"
            return
        }
        if (path.isBlank() || prompt.isBlank() || isCreatingSession) return

        viewModelScope.launch {
            isCreatingSession = true
            createError = null
            try {
                when (val result = socketClient.spawnCodexSession(
                    credentials = credentials,
                    machine = machine,
                    directory = path.trim(),
                    approvedNewDirectoryCreation = approvedNewDirectoryCreation,
                )) {
                    is HappySpawnResult.Success -> {
                        val session = waitForSession(credentials, result.sessionId)
                        if (session == null) {
                            createError = "会话已启动，但同步尚未完成，请刷新后查看"
                        } else {
                            prompt.trim().takeIf(String::isNotBlank)?.let {
                                syncApi.sendMessage(credentials, session, it)
                            }
                            createdSessionId = session.id
                        }
                    }
                    is HappySpawnResult.DirectoryApprovalRequired -> {
                        pendingDirectoryApproval = PendingDirectoryApproval(
                            machineId = machine.id,
                            path = result.directory,
                            prompt = prompt,
                        )
                    }
                    is HappySpawnResult.Error -> createError = result.message
                }
            } catch (_: HappySyncException) {
                createError = "会话启动后同步失败，请刷新后查看"
            } catch (_: Exception) {
                createError = "无法在开发机上启动 Codex，请确认 Happy CLI 在线"
            } finally {
                isCreatingSession = false
            }
        }
    }

    private suspend fun waitForSession(
        credentials: HappyCredentials,
        sessionId: String,
    ): HappySession? {
        repeat(10) {
            val snapshot = syncApi.fetchSnapshot(credentials)
            machines = snapshot.machines
            sessions = snapshot.sessions
            snapshot.sessions.firstOrNull { it.id == sessionId }?.let { return it }
            delay(1_000)
        }
        return null
    }
}

data class PendingDirectoryApproval(
    val machineId: String,
    val path: String,
    val prompt: String,
)

sealed interface WorkflowConnectionStatus {
    data object Disconnected : WorkflowConnectionStatus
    data object Connecting : WorkflowConnectionStatus
    data object Connected : WorkflowConnectionStatus
    data class Error(val message: String) : WorkflowConnectionStatus
}
