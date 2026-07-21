package me.rerere.rikkahub.data.profile

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ProfileMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
    private val service: ProfileMaintenanceService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        service.run()
        Result.success()
    }.getOrElse {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
