package me.rerere.rikkahub.data.agenda

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import me.rerere.rikkahub.data.model.AgendaPlanStage
import me.rerere.rikkahub.data.model.AgendaPlanStageStatus
import java.util.concurrent.TimeUnit

interface AgendaPlanReminderGateway {
    fun sync(stage: AgendaPlanStage)

    fun cancel(stageId: String)
}

class AgendaPlanReminderScheduler(
    private val context: Context,
) : AgendaPlanReminderGateway {
    override fun sync(stage: AgendaPlanStage) {
        cancel(stage.id)
        val reminderAt = stage.reminderAt ?: return
        if (stage.status != AgendaPlanStageStatus.PENDING) return
        val delay = reminderAt - System.currentTimeMillis()
        if (delay <= 0) return

        val input = Data.Builder()
            .putString(AgendaPlanStageReminderWorker.KEY_STAGE_ID, stage.id)
            .putLong(AgendaPlanStageReminderWorker.KEY_EXPECTED_REMINDER_AT, reminderAt)
            .build()
        val request = OneTimeWorkRequestBuilder<AgendaPlanStageReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag(workName(stage.id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(stage.id),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun cancel(stageId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(stageId))
    }

    private fun workName(stageId: String) = "agenda-plan-stage-reminder-$stageId"
}
