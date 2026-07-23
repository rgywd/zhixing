package me.rerere.rikkahub.data.agenda

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import java.util.concurrent.TimeUnit

class AgendaReminderScheduler(private val context: Context) {
    fun sync(task: AgendaTask) {
        cancel(task.id)
        val reminderAt = task.reminderAt ?: return
        if (task.status != AgendaTaskStatus.PENDING) return
        val delay = reminderAt - System.currentTimeMillis()
        if (delay <= 0) return

        val input = Data.Builder()
            .putString(AgendaReminderWorker.KEY_TASK_ID, task.id)
            .putLong(AgendaReminderWorker.KEY_EXPECTED_REMINDER_AT, reminderAt)
            .build()
        val request = OneTimeWorkRequestBuilder<AgendaReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag(workName(task.id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(task.id),
            androidx.work.ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(taskId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(taskId))
    }

    private fun workName(taskId: String) = "agenda-reminder-$taskId"
}
