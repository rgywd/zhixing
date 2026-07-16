package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyApproval
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyCredentials
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyCredentialsStore
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyDecryptionException
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyMessage
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyMachine
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyRpcException
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySession
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySocketClient
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySyncApi
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySyncException
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySpawnResult

class WorkflowSessionVM(
    private val sessionId: String,
    private val credentialsStore: HappyCredentialsStore,
    private val syncApi: HappySyncApi,
    private val socketClient: HappySocketClient,
) : ViewModel() {
    private val refreshSignal = Channel<Unit>(Channel.CONFLATED)
    private val socketSubscription = socketClient.addUpdateListener { refreshSignal.trySend(Unit) }
    private var credentials: HappyCredentials? = credentialsStore.load()

    var session by mutableStateOf<HappySession?>(null)
        private set
    var machines by mutableStateOf<List<HappyMachine>>(emptyList())
        private set
    var messages by mutableStateOf<List<HappyMessage>>(emptyList())
        private set
    var draft by mutableStateOf("")
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isActing by mutableStateOf(false)
        private set
    private var syncError by mutableStateOf<String?>(null)
    private var actionError by mutableStateOf<String?>(null)
    val error: String? get() = actionError ?: syncError
    var notice by mutableStateOf<String?>(null)
        private set
    var resumedSessionId by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            val currentCredentials = credentials
            if (currentCredentials == null) {
                syncError = "Happy 登录已失效，请返回工作流首页重新连接"
                isLoading = false
                return@launch
            }
            runCatching { socketClient.connect(currentCredentials) }
            while (isActive) {
                refreshNow(currentCredentials)
                withTimeoutOrNull(POLL_INTERVAL_MS) { refreshSignal.receive() }
            }
        }
    }

    fun updateDraft(value: String) {
        draft = value
    }

    fun refresh() {
        refreshSignal.trySend(Unit)
    }

    fun send() {
        val text = draft.trim()
        val currentSession = session ?: return
        val currentCredentials = credentials ?: return
        if (text.isBlank() || isActing || !currentSession.active) return
        act("消息已发送") {
            syncApi.sendMessage(currentCredentials, currentSession, text)
            draft = ""
        }
    }

    fun stop() {
        val currentSession = session ?: return
        val currentCredentials = credentials ?: return
        act("已请求停止任务") { socketClient.abort(currentCredentials, currentSession) }
    }

    fun resumeSession() {
        val currentSession = session ?: return
        val currentCredentials = credentials ?: return
        val machine = machines.firstOrNull { it.id == currentSession.machineId }
        if (machine == null || !machine.active) {
            actionError = "原开发机当前离线，无法恢复此对话"
            return
        }
        if (isActing) return
        viewModelScope.launch {
            isActing = true
            actionError = null
            try {
                when (val result = socketClient.resumeSession(currentCredentials, machine, currentSession)) {
                    is HappySpawnResult.Success -> {
                        notice = "会话已恢复"
                        resumedSessionId = result.sessionId
                    }
                    is HappySpawnResult.DirectoryApprovalRequired -> {
                        actionError = "恢复会话时开发机要求创建目录"
                    }
                    is HappySpawnResult.Error -> actionError = result.message
                }
            } catch (throwable: Throwable) {
                actionError = throwable.toUserMessage()
            } finally {
                isActing = false
            }
        }
    }

    fun consumeResumedSession() {
        resumedSessionId = null
    }

    fun approve(approval: HappyApproval, forSession: Boolean) {
        val currentSession = session ?: return
        val currentCredentials = credentials ?: return
        act(if (forSession) "本会话已允许该操作" else "已允许一次") {
            socketClient.approve(currentCredentials, currentSession, approval.id, forSession)
        }
    }

    fun deny(approval: HappyApproval, abort: Boolean) {
        val currentSession = session ?: return
        val currentCredentials = credentials ?: return
        act(if (abort) "已拒绝并请求停止" else "已拒绝该操作") {
            socketClient.deny(currentCredentials, currentSession, approval.id, abort)
        }
    }

    private fun act(successNotice: String, action: suspend () -> Unit) {
        if (isActing) return
        viewModelScope.launch {
            isActing = true
            actionError = null
            try {
                action()
                notice = successNotice
                refreshNow(credentials ?: return@launch)
            } catch (throwable: Throwable) {
                actionError = throwable.toUserMessage()
            } finally {
                isActing = false
            }
        }
    }

    private suspend fun refreshNow(currentCredentials: HappyCredentials) {
        try {
            val snapshot = syncApi.fetchSnapshot(currentCredentials)
            machines = snapshot.machines
            val latestSession = snapshot.sessions
                .firstOrNull { it.id == sessionId }
            if (latestSession != null) session = latestSession
            val currentSession = session
            if (currentSession != null) {
                val afterSeq = messages.maxOfOrNull(HappyMessage::seq) ?: 0
                val nextMessages = syncApi.fetchMessages(currentCredentials, currentSession, afterSeq)
                if (nextMessages.isNotEmpty()) {
                    messages = (messages + nextMessages)
                        .distinctBy(HappyMessage::id)
                        .sortedBy(HappyMessage::seq)
                }
            }
            syncError = if (latestSession == null && session == null) "该会话不在 Happy 历史记录中" else null
        } catch (throwable: Throwable) {
            syncError = throwable.toUserMessage()
        } finally {
            isLoading = false
        }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is HappyDecryptionException -> "此会话无法解密，其他会话不受影响"
        is HappySyncException -> when (statusCode) {
            401, 403 -> "Happy 登录已失效，请重新连接"
            409 -> "会话状态已变化，已重新同步"
            else -> "同步失败（$statusCode）"
        }
        is HappyRpcException.Offline -> "开发机或 Happy 中继当前离线"
        is HappyRpcException.Rejected -> "远程操作被拒绝：${message.orEmpty().substringAfterLast(':').trim()}"
        is kotlinx.coroutines.TimeoutCancellationException -> "远程操作超时，请确认开发机在线"
        is IOException -> "网络连接失败，请稍后重试"
        else -> "操作失败，请稍后重试"
    }

    override fun onCleared() {
        socketSubscription.close()
        super.onCleared()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3_000L
    }
}
