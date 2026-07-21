package me.rerere.rikkahub.data.profile

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.util.concurrent.TimeUnit

class ProfileMaintenanceScheduler(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    suspend fun sync() {
        val settings = settingsStore.settingsFlow.first()
        val config = settings.profileMaintenanceConfig.normalized()
        val workManager = WorkManager.getInstance(context)
        if (!config.enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(CATCH_UP_WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<ProfileMaintenanceWorker>(
            config.intervalHours.toLong(),
            TimeUnit.HOURS,
        ).setConstraints(constraints).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )

        val overdueAt = settings.profileMaintenanceStatus.lastSuccessAt +
            TimeUnit.HOURS.toMillis(config.intervalHours.toLong())
        if (settings.profileMaintenanceStatus.lastSuccessAt == 0L || System.currentTimeMillis() >= overdueAt) {
            val catchUp = OneTimeWorkRequestBuilder<ProfileMaintenanceWorker>()
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniqueWork(CATCH_UP_WORK_NAME, ExistingWorkPolicy.KEEP, catchUp)
        }
    }

    fun runNow() {
        val request = OneTimeWorkRequestBuilder<ProfileMaintenanceWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "profile-maintenance-periodic"
        private const val CATCH_UP_WORK_NAME = "profile-maintenance-catch-up"
        private const val MANUAL_WORK_NAME = "profile-maintenance-manual"
    }
}
