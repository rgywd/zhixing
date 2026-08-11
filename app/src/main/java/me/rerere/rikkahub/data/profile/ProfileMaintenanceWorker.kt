package me.rerere.rikkahub.data.profile

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Compatibility shell for V2 work already persisted by WorkManager before upgrade.
 * It performs no reads, model calls, writes, scheduling, or retries.
 */
class ProfileMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = Result.success()
}
