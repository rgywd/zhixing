package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 周期兜底同步：进程被杀后，Socket 增量链路失效，
 * 由 WorkManager 定期唤起刷新快照并补发关键通知。
 */
class WorkSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val notificationManager: WorkNotificationManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { notificationManager.syncAndNotify() }
        return Result.success()
    }
}
