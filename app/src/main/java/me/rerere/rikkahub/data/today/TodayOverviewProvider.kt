package me.rerere.rikkahub.data.today

import java.time.Instant
import java.time.ZoneId
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
import me.rerere.rikkahub.data.agenda.AgendaAction
import me.rerere.rikkahub.data.agenda.AgendaProjection
import me.rerere.rikkahub.data.agenda.buildAgendaProjection
import me.rerere.rikkahub.data.db.entity.AssistantTaskEntity
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import me.rerere.rikkahub.data.status.MyStatusCoordinator
import me.rerere.rikkahub.data.status.MyStatusSnapshot
import me.rerere.rikkahub.data.task.AssistantTaskRepository
import me.rerere.rikkahub.data.task.AssistantTaskStatus
import me.rerere.rikkahub.data.work.PhoneWorkRepository
import me.rerere.rikkahub.data.work.PhoneWorkSession

internal sealed interface TodayItem {
    val stableId: String

    data class AssistantTask(val task: AssistantTaskEntity) : TodayItem {
        override val stableId: String = "assistant-task:${task.id}"
    }

    data class Agenda(val action: AgendaAction) : TodayItem {
        override val stableId: String = "agenda:${action.stableId}"
    }

    data class CurrentStatus(val snapshot: MyStatusSnapshot) : TodayItem {
        override val stableId: String = "current-status:${snapshot.generatedAtEpochMillis}"
    }

    data class WorkAttention(val session: PhoneWorkSession) : TodayItem {
        override val stableId: String = "work:${session.id}"
    }
}

internal data class TodaySnapshot(
    val items: List<TodayItem>,
    val completedItems: List<TodayItem.AssistantTask>,
    val agendaProjection: AgendaProjection,
    val waitingSessions: List<PhoneWorkSession>,
    val workConfigured: Boolean,
    val agendaActionCount: Int,
    val agendaOverdueCount: Int,
    val generatedAt: Long,
) {
    val isEmpty: Boolean
        get() = items.isEmpty()

    companion object {
        val EMPTY_AGENDA = buildAgendaProjection(emptyList(), emptyList(), nowMillis = 0)
        val EMPTY = TodaySnapshot(
            items = emptyList(),
            completedItems = emptyList(),
            agendaProjection = EMPTY_AGENDA,
            waitingSessions = emptyList(),
            workConfigured = false,
            agendaActionCount = 0,
            agendaOverdueCount = 0,
            generatedAt = 0,
        )
    }
}

internal fun buildTodaySnapshot(
    sessions: List<PhoneWorkSession>,
    tasks: List<AgendaTask>,
    plans: List<AgendaPlanWithStages>,
    nowMillis: Long = System.currentTimeMillis(),
    workConfigured: Boolean = false,
    assistantTasks: List<AssistantTaskEntity> = emptyList(),
    statusSnapshot: MyStatusSnapshot? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
): TodaySnapshot {
    val agendaProjection = buildAgendaProjection(
        tasks = tasks,
        plans = plans,
        nowMillis = nowMillis,
        zoneId = zoneId,
    )
    val waitingSessions = sessions.filter { it.status == "WAITING_FOR_USER" }
    val localDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val activeTasks = assistantTasks.filter {
        it.status in setOf(
            AssistantTaskStatus.WAITING_FOR_INPUT.name,
            AssistantTaskStatus.FAILED_RETRYABLE.name,
            AssistantTaskStatus.RUNNING.name,
        )
    }
    val completedTasks = assistantTasks.filter {
        it.status == AssistantTaskStatus.COMPLETED.name &&
            Instant.ofEpochMilli(it.finishedAt ?: it.updatedAt).atZone(zoneId).toLocalDate() == localDate
    }
    val items = buildList<TodayItem> {
        addAll(activeTasks.map(TodayItem::AssistantTask))
        statusSnapshot?.let { add(TodayItem.CurrentStatus(it)) }
        addAll(agendaProjection.actions.map(TodayItem::Agenda))
        addAll(waitingSessions.map(TodayItem::WorkAttention))
    }.sortedWith(todayItemComparator(nowMillis))

    return TodaySnapshot(
        items = items,
        completedItems = completedTasks
            .sortedByDescending { it.finishedAt ?: it.updatedAt }
            .map(TodayItem::AssistantTask),
        agendaProjection = agendaProjection,
        waitingSessions = waitingSessions,
        workConfigured = workConfigured,
        agendaActionCount = agendaProjection.actions.size,
        agendaOverdueCount = agendaProjection.actions.count {
            it.dueAt != null && it.dueAt!! < nowMillis
        },
        generatedAt = nowMillis,
    )
}

private fun todayItemComparator(nowMillis: Long): Comparator<TodayItem> =
    compareBy<TodayItem>(
        { item ->
            when (item) {
                is TodayItem.AssistantTask -> when (item.task.status) {
                    AssistantTaskStatus.WAITING_FOR_INPUT.name -> 0
                    AssistantTaskStatus.FAILED_RETRYABLE.name -> 1
                    AssistantTaskStatus.RUNNING.name -> 2
                    else -> 6
                }

                is TodayItem.CurrentStatus -> 3
                is TodayItem.Agenda -> 4
                is TodayItem.WorkAttention -> 5
            }
        },
        { item ->
            when (item) {
                is TodayItem.Agenda -> item.action.dueAt ?: Long.MAX_VALUE
                is TodayItem.AssistantTask -> item.task.updatedAt
                else -> nowMillis
            }
        },
        TodayItem::stableId,
    )

internal class TodayOverviewProvider(
    private val appScope: AppScope,
    private val phoneWorkRepository: PhoneWorkRepository,
    agendaTaskRepository: AgendaTaskRepository,
    agendaPlanRepository: AgendaPlanRepository,
    assistantTaskRepository: AssistantTaskRepository,
    private val statusCoordinator: MyStatusCoordinator,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val agendaNow = flow {
        while (true) {
            emit(clock())
            delay(AGENDA_REFRESH_INTERVAL_MS)
        }
    }

    private val workState = combine(
        phoneWorkRepository.credentials.connection,
        phoneWorkRepository.observeActiveSessions(),
    ) { connection, sessions -> connection.configured to sessions }

    private val agendaState = combine(
        agendaTaskRepository.observeVisibleTasks(),
        agendaPlanRepository.observeVisiblePlans(),
        agendaNow,
    ) { tasks, plans, now -> Triple(tasks, plans, now) }

    val state: StateFlow<TodaySnapshot> = combine(
        workState,
        agendaState,
        assistantTaskRepository.observeTasks(),
        statusCoordinator.state,
    ) { (workConfigured, sessions), (tasks, plans, now), assistantTasks, status ->
        buildTodaySnapshot(
            sessions = sessions,
            tasks = tasks,
            plans = plans,
            nowMillis = now,
            workConfigured = workConfigured,
            assistantTasks = assistantTasks,
            statusSnapshot = status.snapshot,
        )
    }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000), TodaySnapshot.EMPTY)

    private val refreshMutex = Mutex()
    private var lastRefreshAtEpochMillis = 0L

    fun onVisible() {
        statusCoordinator.onVisible()
        if (!phoneWorkRepository.credentials.connection.value.configured) return
        appScope.launch {
            refreshMutex.withLock {
                val now = clock()
                if (now - lastRefreshAtEpochMillis < REFRESH_MIN_INTERVAL_MS) return@withLock
                lastRefreshAtEpochMillis = now
                runCatching { phoneWorkRepository.refreshSessions() }
            }
        }
    }

    private companion object {
        const val AGENDA_REFRESH_INTERVAL_MS = 60_000L
        const val REFRESH_MIN_INTERVAL_MS = 60_000L
    }
}
