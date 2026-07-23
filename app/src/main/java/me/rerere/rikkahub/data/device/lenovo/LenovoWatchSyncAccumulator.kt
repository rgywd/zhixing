package me.rerere.rikkahub.data.device.lenovo

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Collects a history replay without exposing intermediate records to the dashboard.
 *
 * The watch sends historical measurements one at a time. Updating the public snapshot for every
 * record makes current values appear to "scan" through the past, so a sync publishes only the
 * final, newest snapshot after the quiet-period completion signal.
 */
internal class LenovoWatchSyncAccumulator(initial: LenovoWatchHealthSnapshot) {
    private var snapshot = initial
    private var hourlyVitalsAt: LocalDateTime? = null
    private var hourlyRecoveryAt: LocalDateTime? = null
    private val measurementTimes = mutableMapOf<LenovoWatchMeasurementKind, LocalDateTime>()
    private val sleepByDay = mutableMapOf<LocalDate, SleepTotals>()
    private var latestSleepDay: LocalDate? = null

    var processedRecords: Int = 0
        private set

    fun accept(event: LenovoWatchEvent): Boolean {
        val accepted = when (event) {
            is LenovoWatchEvent.CurrentActivity -> {
                val reportedSleep = event.shallowSleepMinutes + event.deepSleepMinutes
                snapshot = snapshot.copy(
                    steps = event.steps,
                    calories = event.calories,
                    totalSleepMinutes = if (reportedSleep > 0) {
                        maxOf(reportedSleep, snapshot.totalSleepMinutes ?: 0)
                    } else {
                        snapshot.totalSleepMinutes
                    },
                    shallowSleepMinutes = event.shallowSleepMinutes.takeIf { reportedSleep > 0 }
                        ?: snapshot.shallowSleepMinutes,
                    deepSleepMinutes = event.deepSleepMinutes.takeIf { reportedSleep > 0 }
                        ?: snapshot.deepSleepMinutes,
                    awakeCount = event.awakeCount.takeIf { reportedSleep > 0 || it > 0 }
                        ?: snapshot.awakeCount,
                    exerciseSeconds = event.exerciseSeconds,
                    exerciseCount = event.exerciseCount,
                )
                true
            }

            is LenovoWatchEvent.HourlyVitals -> {
                if (event.recordedHour.isNewerThan(hourlyVitalsAt)) {
                    hourlyVitalsAt = event.recordedHour
                    snapshot = snapshot.copy(
                        heartRate = event.heartRate.takeIf { it > 0 } ?: snapshot.heartRate,
                        bloodOxygen = event.bloodOxygen.takeIf { it > 0 } ?: snapshot.bloodOxygen,
                        exerciseSeconds = event.exerciseSeconds ?: snapshot.exerciseSeconds,
                        exerciseCount = event.exerciseCount ?: snapshot.exerciseCount,
                    )
                }
                true
            }

            is LenovoWatchEvent.HourlyRecovery -> {
                if (event.recordedHour.isNewerThan(hourlyRecoveryAt)) {
                    hourlyRecoveryAt = event.recordedHour
                    snapshot = snapshot.copy(
                        immunity = event.immunity.takeIf { it > 0 } ?: snapshot.immunity,
                        temperatureCelsius = event.temperatureCelsius.takeIf { it > 0 } ?: snapshot.temperatureCelsius,
                    )
                }
                true
            }

            is LenovoWatchEvent.Measurement -> {
                val previous = measurementTimes[event.kind]
                if (event.recordedAt.isNewerThan(previous)) {
                    measurementTimes[event.kind] = event.recordedAt
                    snapshot = snapshot.withMeasurement(event.kind, event.primaryValue, event.secondaryValue)
                }
                true
            }

            is LenovoWatchEvent.InstantMeasurement -> {
                snapshot = snapshot.withMeasurement(event.kind, event.primaryValue, event.secondaryValue)
                true
            }

            is LenovoWatchEvent.OneKeyMeasurement -> {
                snapshot = snapshot.copy(
                    heartRate = event.heartRate.takeIf { it > 0 } ?: snapshot.heartRate,
                    bloodOxygen = event.bloodOxygen.takeIf { it >= 60 } ?: snapshot.bloodOxygen,
                    systolic = event.systolic.takeIf { it > 0 } ?: snapshot.systolic,
                    diastolic = event.diastolic.takeIf { it > 0 } ?: snapshot.diastolic,
                )
                true
            }

            is LenovoWatchEvent.SleepSegment -> {
                acceptSleep(event)
                true
            }
            else -> false
        }
        if (accepted) processedRecords += 1
        return accepted
    }

    fun result(): LenovoWatchHealthSnapshot = snapshot

    private fun acceptSleep(event: LenovoWatchEvent.SleepSegment) {
        if (event.durationMinutes !in 1 until MAX_SLEEP_SEGMENT_MINUTES) return
        if (event.rawType !in KNOWN_SLEEP_TYPES) return
        // Type 17 is rendered as a nap by the official app. Keep it out of the overnight
        // dashboard until naps have their own product surface.
        if (event.rawType == SLEEP_TYPE_RAP) return

        val sleepDay = event.startedAt.toLocalDate().let { date ->
            if (event.startedAt.hour >= SLEEP_DAY_BOUNDARY_HOUR) date.plusDays(1) else date
        }
        val totals = sleepByDay.getOrPut(sleepDay) { SleepTotals() }
        when (event.rawType) {
            SLEEP_TYPE_SHALLOW -> totals.typeOneMinutes += event.durationMinutes
            SLEEP_TYPE_DEEP -> totals.deepMinutes += event.durationMinutes
            SLEEP_TYPE_REM -> totals.remMinutes += event.durationMinutes
            SLEEP_TYPE_AWAKE -> totals.awakeCount += 1
        }

        val currentLatest = latestSleepDay
        if (currentLatest == null || !sleepDay.isBefore(currentLatest)) {
            latestSleepDay = maxOf(sleepDay, currentLatest ?: sleepDay)
            publishLatestSleep()
        }
    }

    private fun publishLatestSleep() {
        val totals = latestSleepDay?.let(sleepByDay::get) ?: return
        // Lenovo's newer sleep replay includes REM records and reports type 1 as the night's
        // total asleep time; deep and REM are breakdowns of that total. Older replays contain
        // no REM and use type 1 as shallow sleep, so retain the legacy additive interpretation.
        val hasNewSleepBreakdown = totals.remMinutes > 0
        val totalSleepMinutes = if (hasNewSleepBreakdown) {
            totals.typeOneMinutes
        } else {
            totals.typeOneMinutes + totals.deepMinutes
        }
        val shallowSleepMinutes = if (hasNewSleepBreakdown) {
            (totalSleepMinutes - totals.deepMinutes - totals.remMinutes).coerceAtLeast(0)
        } else {
            totals.typeOneMinutes
        }
        snapshot = snapshot.copy(
            totalSleepMinutes = totalSleepMinutes,
            shallowSleepMinutes = shallowSleepMinutes,
            deepSleepMinutes = totals.deepMinutes,
            awakeCount = totals.awakeCount,
        )
    }

    private fun LocalDateTime.isNewerThan(other: LocalDateTime?): Boolean = other == null || isAfter(other)

    private fun LenovoWatchHealthSnapshot.withMeasurement(
        kind: LenovoWatchMeasurementKind,
        primary: Double,
        secondary: Double?,
    ): LenovoWatchHealthSnapshot = when (kind) {
        LenovoWatchMeasurementKind.HEART_RATE -> copy(heartRate = primary.toInt().takeIf { it > 0 } ?: heartRate)
        LenovoWatchMeasurementKind.BLOOD_OXYGEN -> copy(bloodOxygen = primary.toInt().takeIf { it >= 60 } ?: bloodOxygen)
        LenovoWatchMeasurementKind.BLOOD_PRESSURE -> copy(
            systolic = primary.toInt().takeIf { it > 0 } ?: systolic,
            diastolic = secondary?.toInt()?.takeIf { it > 0 } ?: diastolic,
        )
        LenovoWatchMeasurementKind.TEMPERATURE -> copy(temperatureCelsius = primary.takeIf { it > 0 } ?: temperatureCelsius)
        LenovoWatchMeasurementKind.IMMUNITY -> copy(immunity = primary.toInt().takeIf { it > 0 } ?: immunity)
        LenovoWatchMeasurementKind.PRESSURE -> this
    }

    private data class SleepTotals(
        var typeOneMinutes: Int = 0,
        var deepMinutes: Int = 0,
        var remMinutes: Int = 0,
        var awakeCount: Int = 0,
    )

    private companion object {
        const val SLEEP_DAY_BOUNDARY_HOUR = 12
        const val MAX_SLEEP_SEGMENT_MINUTES = 720
        const val SLEEP_TYPE_SHALLOW = 1
        const val SLEEP_TYPE_DEEP = 2
        const val SLEEP_TYPE_REM = 3
        const val SLEEP_TYPE_AWAKE = 4
        const val SLEEP_TYPE_RAP = 17
        val KNOWN_SLEEP_TYPES = setOf(
            SLEEP_TYPE_SHALLOW,
            SLEEP_TYPE_DEEP,
            SLEEP_TYPE_REM,
            SLEEP_TYPE_AWAKE,
            SLEEP_TYPE_RAP,
        )
    }
}
