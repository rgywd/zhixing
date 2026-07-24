package me.rerere.rikkahub.data.agenda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import org.koin.core.context.GlobalContext

internal enum class AgendaReminderSource {
    PLANS,
    TASKS,
}

internal suspend fun reconcileAgendaReminderSources(
    reconcilePlans: suspend () -> Unit,
    reconcileTasks: suspend () -> Unit,
    onFailure: (AgendaReminderSource, Throwable) -> Unit,
) {
    reconcileAgendaReminderSource(AgendaReminderSource.PLANS, reconcilePlans, onFailure)
    reconcileAgendaReminderSource(AgendaReminderSource.TASKS, reconcileTasks, onFailure)
}

private suspend fun reconcileAgendaReminderSource(
    source: AgendaReminderSource,
    reconcile: suspend () -> Unit,
    onFailure: (AgendaReminderSource, Throwable) -> Unit,
) {
    try {
        reconcile()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(source, error)
    }
}

class AgendaReminderReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RECONCILE_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                reconcileAgendaReminderSources(
                    reconcilePlans = { GlobalContext.get().get<AgendaPlanRepository>().reconcileReminders() },
                    reconcileTasks = { GlobalContext.get().get<AgendaTaskRepository>().reconcileReminders() },
                    onFailure = { source, error ->
                        Log.w(TAG, "Unable to reconcile ${source.name.lowercase()} agenda reminders", error)
                    },
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AgendaReminderReconcile"

        val RECONCILE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
