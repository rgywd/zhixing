package me.rerere.rikkahub.data.agenda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.AgendaPlanRepository
import me.rerere.rikkahub.data.repository.AgendaTaskRepository
import org.koin.core.context.GlobalContext

class AgendaReminderReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                GlobalContext.get().get<AgendaPlanRepository>().reconcileReminders()
                GlobalContext.get().get<AgendaTaskRepository>().reconcileReminders()
            }
            pendingResult.finish()
        }
    }
}
