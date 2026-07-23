package me.rerere.rikkahub.data.status

import android.content.Context
import me.rerere.rikkahub.data.agenda.AgendaAction
import me.rerere.rikkahub.data.agenda.AgendaPlanProjection
import me.rerere.rikkahub.data.agenda.buildAgendaProjection
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbe
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchSyncStore
import me.rerere.rikkahub.data.model.AgendaPlanWithStages
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import java.time.Instant
import java.time.ZoneId

internal class LenovoWatchBodyStatusSource(
    context: Context,
    private val watchProbe: LenovoWatchProbe,
) : MyStatusBodySource {
    private val syncStore = LenovoWatchSyncStore(context)

    override suspend fun current(): MyStatusBodyFacts? {
        val health = watchProbe.state.value.health
        val sleepMinutes = health.totalSleepMinutes
            ?: listOfNotNull(health.shallowSleepMinutes, health.deepSleepMinutes)
                .takeIf(List<Int>::isNotEmpty)
                ?.sum()
        val observedAt = syncStore.lastSuccessfulSync()
            ?.atZone(ZoneId.systemDefault())
            ?.toInstant()
            ?.toEpochMilli()
            ?.let(::formatInstant)
        return MyStatusBodyFacts(
            sleepMinutes = sleepMinutes,
            deepSleepMinutes = health.deepSleepMinutes,
            heartRateBpm = health.heartRate,
            bloodOxygenPercent = health.bloodOxygen,
            steps = health.steps,
            caloriesKcal = health.calories,
            exerciseCount = health.exerciseCount,
            observedAt = observedAt,
        ).takeIf(MyStatusBodyFacts::hasData)
    }
}

internal class LocalAgendaStatusSource(
    private val taskRepository: AgendaTaskRepository,
    private val planRepository: AgendaPlanRepository,
    private val clock: MyStatusClock = MyStatusClock(System::currentTimeMillis),
) : MyStatusAgendaSource {
    override suspend fun current(): MyStatusAgendaFacts {
        val now = clock.nowEpochMillis()
        return buildMyStatusAgendaFacts(
            tasks = taskRepository.getVisibleTasks(),
            plans = planRepository.getVisiblePlans(),
            nowEpochMillis = now,
        )
    }
}

internal fun buildMyStatusAgendaFacts(
    tasks: List<AgendaTask>,
    plans: List<AgendaPlanWithStages>,
    nowEpochMillis: Long,
): MyStatusAgendaFacts {
    val projection = buildAgendaProjection(tasks, plans, nowMillis = nowEpochMillis)
    val candidates = buildList {
        projection.actions.forEach { action ->
            add(
                MyStatusAgendaCandidate(
                    stableId = action.stableId,
                    title = when (action) {
                        is AgendaAction.Task -> action.title
                        is AgendaAction.PlanStage -> "${action.context} · ${action.title}"
                    },
                    at = action.actionAt ?: action.dueAt,
                    overdue = action.dueAt?.let { it < nowEpochMillis } == true,
                )
            )
        }
        projection.futureTasks.forEach { task ->
            add(
                MyStatusAgendaCandidate(
                    stableId = "task:${task.id}",
                    title = task.title,
                    at = task.dueAt,
                    overdue = false,
                )
            )
        }
        (projection.upcomingPlans + projection.waitingPlans).forEach { plan ->
            add(plan.toMyStatusCandidate())
        }
    }
    val nextItems = candidates
        .sortedWith(compareBy<MyStatusAgendaCandidate> { it.at == null }.thenBy { it.at ?: Long.MAX_VALUE })
        .take(3)
        .map { item ->
            MyStatusAgendaItem(
                evidenceId = "agenda.item.${item.stableId}",
                title = item.title.take(MAX_AGENDA_TITLE_LENGTH),
                dueAt = item.at?.let(::formatInstant),
                timing = agendaTiming(item, nowEpochMillis),
            )
        }
    return MyStatusAgendaFacts(
        pendingCount = projection.actions.size +
            projection.futureTasks.size +
            projection.upcomingPlans.size +
            projection.waitingPlans.size,
        overdueCount = projection.actions.count { action ->
            action.dueAt?.let { it < nowEpochMillis } == true
        },
        nextItems = nextItems,
        observedAt = formatInstant(nowEpochMillis),
    )
}

private fun AgendaPlanProjection.toMyStatusCandidate(): MyStatusAgendaCandidate {
    val stage = currentStage
    return MyStatusAgendaCandidate(
        stableId = "plan:${value.plan.id}",
        title = stage?.let { "${value.plan.title} · ${it.title}" } ?: value.plan.title,
        at = nextAt ?: value.plan.eventAt,
        overdue = false,
    )
}

private data class MyStatusAgendaCandidate(
    val stableId: String,
    val title: String,
    val at: Long?,
    val overdue: Boolean,
)

private fun agendaTiming(
    item: MyStatusAgendaCandidate,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): MyStatusAgendaTiming {
    if (item.overdue) return MyStatusAgendaTiming.OVERDUE
    val at = item.at ?: return MyStatusAgendaTiming.UNDATED
    if (at <= nowEpochMillis + DUE_SOON_WINDOW_MS) return MyStatusAgendaTiming.DUE_SOON
    val dueDate = Instant.ofEpochMilli(at).atZone(zoneId).toLocalDate()
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
    return if (dueDate == today) MyStatusAgendaTiming.TODAY else MyStatusAgendaTiming.UPCOMING
}

private const val MAX_AGENDA_TITLE_LENGTH = 80
private const val DUE_SOON_WINDOW_MS = 2 * 60 * 60 * 1_000L
