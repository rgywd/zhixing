package me.rerere.rikkahub.data.device.lenovo

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class LenovoWatchAutoSyncSchedulerTest {
    @Test
    fun `missing or expired successful sync is due immediately`() {
        val now = Instant.parse("2026-07-23T09:00:00Z")
        val scheduler = LenovoWatchAutoSyncScheduler()

        assertEquals(
            LenovoWatchAutoSyncPlan.SyncNow,
            scheduler.plan(
                stage = LenovoWatchProbeStage.READY,
                lastSuccessfulSyncAt = null,
                now = now,
            ),
        )

        val expiredScheduler = LenovoWatchAutoSyncScheduler()
        assertEquals(
            LenovoWatchAutoSyncPlan.SyncNow,
            expiredScheduler.plan(
                stage = LenovoWatchProbeStage.READY,
                lastSuccessfulSyncAt = now.minus(Duration.ofMinutes(31)),
                now = now,
            ),
        )

        val boundaryScheduler = LenovoWatchAutoSyncScheduler()
        assertEquals(
            LenovoWatchAutoSyncPlan.SyncNow,
            boundaryScheduler.plan(
                stage = LenovoWatchProbeStage.READY,
                lastSuccessfulSyncAt = now.minus(Duration.ofMinutes(30)),
                now = now,
            ),
        )
    }

    @Test
    fun `fresh data waits until the thirty minute boundary`() {
        val now = Instant.parse("2026-07-23T09:00:00Z")
        val scheduler = LenovoWatchAutoSyncScheduler()

        assertEquals(
            LenovoWatchAutoSyncPlan.Wait(Duration.ofMinutes(20).toMillis()),
            scheduler.plan(
                stage = LenovoWatchProbeStage.READY,
                lastSuccessfulSyncAt = now.minus(Duration.ofMinutes(10)),
                now = now,
            ),
        )
    }

    @Test
    fun `repeated ready observations cannot trigger overlapping syncs`() {
        val now = Instant.parse("2026-07-23T09:00:00Z")
        val scheduler = LenovoWatchAutoSyncScheduler()

        assertEquals(
            LenovoWatchAutoSyncPlan.SyncNow,
            scheduler.plan(LenovoWatchProbeStage.READY, null, now),
        )
        assertEquals(
            LenovoWatchAutoSyncPlan.Idle,
            scheduler.plan(LenovoWatchProbeStage.READY, null, now.plusSeconds(1)),
        )
        assertEquals(
            LenovoWatchAutoSyncPlan.Idle,
            scheduler.plan(LenovoWatchProbeStage.SYNCING, null, now.plusSeconds(2)),
        )
        assertEquals(
            LenovoWatchAutoSyncPlan.Wait(Duration.ofMinutes(30).toMillis()),
            scheduler.plan(LenovoWatchProbeStage.READY, now.plusSeconds(3), now.plusSeconds(3)),
        )
    }
}
