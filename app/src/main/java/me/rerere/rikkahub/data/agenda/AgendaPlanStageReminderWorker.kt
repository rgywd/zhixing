package me.rerere.rikkahub.data.agenda

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.AGENDA_REMINDER_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.repository.AgendaPlanRepository

class AgendaPlanStageReminderWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repository: AgendaPlanRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val stageId = inputData.getString(KEY_STAGE_ID) ?: return Result.failure()
        val expectedReminderAt = inputData.getLong(KEY_EXPECTED_REMINDER_AT, Long.MIN_VALUE)
        if (expectedReminderAt == Long.MIN_VALUE) return Result.failure()
        val payload = repository.reminderPayload(stageId, expectedReminderAt) ?: return Result.success()
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        val launchIntent = Intent(applicationContext, RouteActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_AGENDA_PLAN_ID, payload.planId)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            stageId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, AGENDA_REMINDER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(payload.title)
            .setContentText(payload.note)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.note))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(stageId.hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val KEY_STAGE_ID = "stage_id"
        const val KEY_EXPECTED_REMINDER_AT = "expected_reminder_at"
        const val EXTRA_AGENDA_PLAN_ID = "agendaPlanId"
    }
}
