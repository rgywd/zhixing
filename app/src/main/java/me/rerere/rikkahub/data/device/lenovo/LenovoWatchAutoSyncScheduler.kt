package me.rerere.rikkahub.data.device.lenovo

import java.time.Duration
import java.time.Instant

internal sealed interface LenovoWatchAutoSyncPlan {
    data object SyncNow : LenovoWatchAutoSyncPlan
    data class Wait(val delayMillis: Long) : LenovoWatchAutoSyncPlan
    data object Idle : LenovoWatchAutoSyncPlan
}

/**
 * Pure scheduling state for process-scoped watch sync.
 *
 * The pending flag closes the small window between deciding to sync and the probe publishing its
 * SYNCING state. The probe also rejects sync calls unless it is READY, so both scheduling and the
 * transport enforce single-flight behavior.
 */
internal class LenovoWatchAutoSyncScheduler(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    private var triggerPending = false

    fun plan(
        stage: LenovoWatchProbeStage,
        lastSuccessfulSyncAt: Instant?,
        now: Instant,
    ): LenovoWatchAutoSyncPlan {
        if (stage != LenovoWatchProbeStage.READY) {
            triggerPending = false
            return LenovoWatchAutoSyncPlan.Idle
        }
        if (triggerPending) return LenovoWatchAutoSyncPlan.Idle

        val delayMillis = lastSuccessfulSyncAt
            ?.plusMillis(intervalMillis)
            ?.toEpochMilli()
            ?.minus(now.toEpochMilli())
            ?: 0L
        if (delayMillis <= 0L) {
            triggerPending = true
            return LenovoWatchAutoSyncPlan.SyncNow
        }
        return LenovoWatchAutoSyncPlan.Wait(delayMillis)
    }

    internal companion object {
        val DEFAULT_INTERVAL_MILLIS: Long = Duration.ofMinutes(30).toMillis()
    }
}
