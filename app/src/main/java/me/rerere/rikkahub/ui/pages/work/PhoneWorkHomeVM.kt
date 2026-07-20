package me.rerere.rikkahub.ui.pages.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.work.PhoneWorkConnection
import me.rerere.rikkahub.data.work.PhoneWorkRepository
import me.rerere.rikkahub.data.work.PhoneWorkSession

class PhoneWorkHomeVM(
    private val repository: PhoneWorkRepository,
) : ViewModel() {
    val sessions: StateFlow<List<PhoneWorkSession>> = repository.observeSessions().stateIn(
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
}
