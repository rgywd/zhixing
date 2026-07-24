package me.rerere.rikkahub.data.today

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agenda.buildAgendaProjection
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import me.rerere.rikkahub.data.work.PhoneWorkRepository
import me.rerere.rikkahub.data.work.PhoneWorkSession

internal data class TodaySnapshot(
    val waitingSessions: List<PhoneWorkSession>,
    val agendaActionCount: Int,
    val agendaOverdueCount: Int,
) {
    val isEmpty: Boolean
        get() = waitingSessions.isEmpty() && agendaActionCount == 0

    companion object {
        val EMPTY = TodaySnapshot(
            waitingSessions = emptyList(),
            agendaActionCount = 0,
            agendaOverdueCount = 0,
        )
    }
}

internal fun buildTodaySnapshot(
    sessions: List<PhoneWorkSession>,
    tasks: List<AgendaTask>,
    plans: List<AgendaPlanWithStages>,
    nowMillis: Long = System.currentTimeMillis(),
): TodaySnapshot {
    val projection = buildAgendaProjection(tasks = tasks, plans = plans, nowMillis = nowMillis)
    return TodaySnapshot(
        // observeActiveSessions 已按 updated_at DESC 排序，过滤后保序
        waitingSessions = sessions.filter { it.status == "WAITING_FOR_USER" },
        agendaActionCount = projection.actions.size,
        agendaOverdueCount = projection.actions.count { it.dueAt != null && it.dueAt!! < nowMillis },
    )
}

internal class TodayOverviewProvider(
    private val appScope: AppScope,
    private val phoneWorkRepository: PhoneWorkRepository,
    agendaTaskRepository: AgendaTaskRepository,
    agendaPlanRepository: AgendaPlanRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val agendaNow = flow {
        while (true) {
            emit(clock())
            delay(AGENDA_REFRESH_INTERVAL_MS)
        }
    }

    val state: StateFlow<TodaySnapshot> = combine(
        phoneWorkRepository.observeActiveSessions(),
        agendaTaskRepository.observeVisibleTasks(),
        agendaPlanRepository.observeVisiblePlans(),
        agendaNow,
    ) { sessions, tasks, plans, now ->
        buildTodaySnapshot(sessions, tasks, plans, now)
    }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000), TodaySnapshot.EMPTY)

    private val refreshMutex = Mutex()
    private var lastRefreshAtEpochMillis = 0L

    fun onVisible() {
        if (!phoneWorkRepository.credentials.connection.value.configured) return
        appScope.launch {
            refreshMutex.withLock {
                val now = clock()
                if (now - lastRefreshAtEpochMillis < REFRESH_MIN_INTERVAL_MS) return@withLock
                lastRefreshAtEpochMillis = now
                // Room 缓存兜底,刷新失败只是数据旧一点
                runCatching { phoneWorkRepository.refreshSessions() }
            }
        }
    }

    private companion object {
        const val AGENDA_REFRESH_INTERVAL_MS = 60_000L
        const val REFRESH_MIN_INTERVAL_MS = 60_000L
    }
}
