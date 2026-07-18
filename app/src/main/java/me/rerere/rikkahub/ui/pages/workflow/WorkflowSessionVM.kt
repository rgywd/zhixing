package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.workflow.WorkApproval
import me.rerere.rikkahub.data.workflow.WorkMachine
import me.rerere.rikkahub.data.workflow.WorkMessage
import me.rerere.rikkahub.data.workflow.WorkRepository
import me.rerere.rikkahub.data.workflow.WorkSession
import me.rerere.rikkahub.data.workflow.WorkSpawnOutcome

class WorkflowSessionVM(
    private val sessionId: String,
    private val repository: WorkRepository,
) : ViewModel() {
    var session by mutableStateOf<WorkSession?>(null)
        private set
    var machines by mutableStateOf<List<WorkMachine>>(emptyList())
        private set
    var messages by mutableStateOf<List<WorkMessage>>(emptyList())
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
    var deleted by mutableStateOf(false)
        private set

    /** 会话级执行模式；随每条消息显式下发，CLI 侧粘滞 */
    var fullAccess by mutableStateOf(false)
        private set
    private var modeInitialized = false

    // 会话被远端删除时 observeSession 会发出 null，但已展示过的数据保留
    private var sessionSeen = false

    init {
        viewModelScope.launch {
            repository.observeSession(sessionId).collect { latest ->
                if (latest != null) {
                    session = latest
                    sessionSeen = true
                    if (!modeInitialized) {
                        modeInitialized = true
                        fullAccess = if (latest.lastPermissionMode != null) {
                            latest.isFullAccess
                        } else {
                            // 没有本地记录时回退到仓库预设的默认策略
                            repository.findPresetForSession(sessionId)?.fullAccess ?: false
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.observeMessages(sessionId).collect { messages = it }
        }
        viewModelScope.launch { repository.observeMachines().collect { machines = it } }
        viewModelScope.launch {
            repository.ensureRealtime()
            syncNow()
            isLoading = false
            repository.updates.conflate().collect {
                syncNow()
                delay(SYNC_THROTTLE_MS)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { syncNow() }
    }

    fun updateFullAccess(value: Boolean) {
        fullAccess = value
        modeInitialized = true
    }

    /** @return true 表示已受理发送（调用方可清空输入框） */
    fun send(text: String): Boolean {
        val trimmed = text.trim()
        val currentSession = session ?: return false
        if (trimmed.isBlank() || isActing || !currentSession.active) return false
        act("消息已发送") {
            repository.sendMessage(sessionId, trimmed, fullAccess = fullAccess)
        }
        return true
    }

    fun stop() {
        if (session == null) return
        act("已请求停止任务") { repository.abort(sessionId) }
    }

    fun delete() {
        if (session == null || isActing) return
        viewModelScope.launch {
            isActing = true
            actionError = null
            try {
                repository.deleteSession(sessionId)
                deleted = true
            } catch (throwable: Throwable) {
                actionError = throwable.toWorkflowMessage()
            } finally {
                isActing = false
            }
        }
    }

    fun resumeSession() {
        if (session == null || isActing) return
        viewModelScope.launch {
            isActing = true
            actionError = null
            try {
                when (val outcome = repository.resumeSession(sessionId)) {
                    is WorkSpawnOutcome.Success -> {
                        notice = "会话已恢复"
                        resumedSessionId = outcome.sessionId
                    }
                    is WorkSpawnOutcome.NeedsDirectoryApproval ->
                        actionError = "恢复会话时开发机要求创建目录"
                    is WorkSpawnOutcome.Error -> actionError = outcome.message
                }
            } catch (throwable: Throwable) {
                actionError = throwable.toWorkflowMessage()
            } finally {
                isActing = false
            }
        }
    }

    fun consumeResumedSession() {
        resumedSessionId = null
    }

    fun approve(approval: WorkApproval, forSession: Boolean) {
        act(if (forSession) "本会话已允许该操作" else "已允许一次") {
            repository.approve(sessionId, approval.id, forSession)
        }
    }

    fun deny(approval: WorkApproval, abort: Boolean) {
        act(if (abort) "已拒绝并请求停止" else "已拒绝该操作") {
            repository.deny(sessionId, approval.id, abort)
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
                syncNow()
            } catch (throwable: Throwable) {
                actionError = throwable.toWorkflowMessage()
            } finally {
                isActing = false
            }
        }
    }

    private suspend fun syncNow() {
        try {
            repository.refreshSnapshot()
            val exists = repository.getSession(sessionId) != null
            if (exists) repository.syncMessages(sessionId)
            syncError = if (!exists && !sessionSeen) "该会话不在 Happy 历史记录中" else null
        } catch (throwable: Throwable) {
            syncError = throwable.toWorkflowMessage()
        }
    }

    private companion object {
        const val SYNC_THROTTLE_MS = 1_500L
    }
}
