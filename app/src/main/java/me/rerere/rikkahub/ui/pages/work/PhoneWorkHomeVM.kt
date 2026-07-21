package me.rerere.rikkahub.ui.pages.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.work.PhoneWorkConnection
import me.rerere.rikkahub.data.work.PhoneWorkRepository
import me.rerere.rikkahub.data.work.PhoneWorkSession

class PhoneWorkHomeVM(
    private val repository: PhoneWorkRepository,
) : ViewModel() {
    val showArchived = MutableStateFlow(false)
    val sessions: StateFlow<List<PhoneWorkSession>> = showArchived.flatMapLatest { archived ->
        if (archived) repository.observeArchivedSessions() else repository.observeSessions()
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val connection: StateFlow<PhoneWorkConnection> = repository.credentials.connection
    val refreshing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    init {
        refresh()
    }

    fun refresh() {
        if (!connection.value.configured || refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            runCatching {
                repository.refreshCatalog()
                repository.refreshSessions()
            }.onSuccess { error.value = null }
                .onFailure { error.value = it.message ?: "Work 同步失败" }
            refreshing.value = false
        }
    }

    fun complete(sessionId: String) {
        viewModelScope.launch {
            runCatching { repository.complete(sessionId) }
                .onFailure { error.value = it.message ?: "结束会话失败" }
        }
    }

    fun toggleArchived() {
        showArchived.value = !showArchived.value
    }

    fun archive(sessionId: String) {
        viewModelScope.launch {
            runCatching { repository.archive(sessionId) }
                .onFailure { error.value = it.message ?: "归档会话失败" }
        }
    }

    fun unarchive(sessionId: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.unarchive(sessionId) }
                .onSuccess {
                    error.value = null
                    onSuccess()
                }
                .onFailure { error.value = it.message ?: "恢复会话失败" }
        }
    }
}
