package me.rerere.rikkahub.data.profile

import android.content.Context
import androidx.work.WorkManager

/** Cancels unique work names left by V2. It never reads chats, invokes a model, or enqueues work. */
class LegacyProfileMaintenanceCleanup(
    private val context: Context,
) {
    fun cancel() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(CATCH_UP_WORK_NAME)
        workManager.cancelUniqueWork(MANUAL_WORK_NAME)
    }

    private companion object {
        const val PERIODIC_WORK_NAME = "profile-maintenance-periodic"
        const val CATCH_UP_WORK_NAME = "profile-maintenance-catch-up"
        const val MANUAL_WORK_NAME = "profile-maintenance-manual"
    }
}
