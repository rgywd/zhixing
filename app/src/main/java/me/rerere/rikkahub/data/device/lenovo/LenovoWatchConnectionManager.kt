package me.rerere.rikkahub.data.device.lenovo

import java.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope

/**
 * Owns remembered-device recovery and periodic sync for the lifetime of the app process.
 *
 * It intentionally does not use a foreground service or promise execution after Android stops the
 * process. First pairing remains a user action in Settings > My devices.
 */
internal class LenovoWatchConnectionManager(
    private val probe: LenovoWatchProbe,
    private val appScope: AppScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var observeJob: Job? = null

    fun start() {
        if (observeJob?.isActive == true) return
        observeJob = appScope.launch {
            val initial = probe.state.value
            if (
                initial.remembered &&
                initial.autoReconnectEnabled &&
                initial.stage in setOf(LenovoWatchProbeStage.IDLE, LenovoWatchProbeStage.ERROR)
            ) {
                probe.start()
            }

            val scheduler = LenovoWatchAutoSyncScheduler()
            probe.state.collectLatest { observed ->
                var current = observed
                while (current.stage == LenovoWatchProbeStage.READY) {
                    when (
                        val plan = scheduler.plan(
                            stage = current.stage,
                            lastSuccessfulSyncAt = current.lastSuccessfulSyncAt,
                            now = clock.instant(),
                        )
                    ) {
                        LenovoWatchAutoSyncPlan.SyncNow -> {
                            probe.sync()
                            return@collectLatest
                        }

                        is LenovoWatchAutoSyncPlan.Wait -> delay(plan.delayMillis)
                        LenovoWatchAutoSyncPlan.Idle -> return@collectLatest
                    }
                    current = probe.state.value
                }
                scheduler.plan(
                    stage = current.stage,
                    lastSuccessfulSyncAt = current.lastSuccessfulSyncAt,
                    now = clock.instant(),
                )
            }
        }
    }
}
