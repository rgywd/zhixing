package me.rerere.rikkahub.data.device.lenovo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LenovoWatchSyncAccumulatorTest {
    @Test
    fun historyReplayIsBufferedAndPublishesNewestValuesOnly() {
        val initial = LenovoWatchHealthSnapshot(steps = 100, heartRate = 70)
        val accumulator = LenovoWatchSyncAccumulator(initial)

        accumulator.accept(hourlyVitals(hour = 12, heartRate = 82, bloodOxygen = 97))
        accumulator.accept(hourlyVitals(hour = 8, heartRate = 61, bloodOxygen = 94))
        accumulator.accept(
            LenovoWatchEvent.CurrentActivity(
                steps = 1_799,
                calories = 32,
                shallowSleepMinutes = 0,
                deepSleepMinutes = 0,
                awakeCount = 0,
                exerciseSeconds = 0,
                exerciseCount = 0,
            ),
        )

        // The caller owns publication; accepting history never mutates its original dashboard value.
        assertEquals(100, initial.steps)
        assertEquals(70, initial.heartRate)
        assertEquals(1_799, accumulator.result().steps)
        assertEquals(82, accumulator.result().heartRate)
        assertEquals(97, accumulator.result().bloodOxygen)
        assertEquals(3, accumulator.processedRecords)
    }

    @Test
    fun newestDatedMeasurementWinsEvenWhenFramesArriveOutOfOrder() {
        val accumulator = LenovoWatchSyncAccumulator(LenovoWatchHealthSnapshot())
        accumulator.accept(measurement(hour = 12, value = 86.0))
        accumulator.accept(measurement(hour = 9, value = 59.0))

        assertEquals(86, accumulator.result().heartRate)
        assertNull(accumulator.result().temperatureCelsius)
    }

    @Test
    fun latestSleepDayIncludesRemAndRapButExcludesAwakeDuration() {
        val accumulator = LenovoWatchSyncAccumulator(LenovoWatchHealthSnapshot())

        accumulator.accept(sleepSegment(LocalDate.of(2026, 7, 22), 23, type = 1, duration = 120))
        accumulator.accept(sleepSegment(LocalDate.of(2026, 7, 23), 1, type = 2, duration = 90))
        accumulator.accept(sleepSegment(LocalDate.of(2026, 7, 23), 3, type = 3, duration = 40))
        accumulator.accept(sleepSegment(LocalDate.of(2026, 7, 23), 4, type = 17, duration = 20))
        accumulator.accept(sleepSegment(LocalDate.of(2026, 7, 23), 5, type = 4, duration = 12))

        val result = accumulator.result()
        assertEquals(270, result.totalSleepMinutes)
        assertEquals(120, result.shallowSleepMinutes)
        assertEquals(90, result.deepSleepMinutes)
        assertEquals(1, result.awakeCount)
    }

    @Test
    fun newestNightWinsWhenSeveralDaysAreReplayedOutOfOrder() {
        val accumulator = LenovoWatchSyncAccumulator(LenovoWatchHealthSnapshot())

        accumulator.accept(sleepSegment(LocalDate.of(2026, 7, 22), 23, type = 1, duration = 180))
        accumulator.accept(sleepSegment(LocalDate.of(2026, 7, 21), 23, type = 2, duration = 300))
        accumulator.accept(sleepSegment(LocalDate.of(2026, 7, 23), 2, type = 2, duration = 100))

        val result = accumulator.result()
        assertEquals(280, result.totalSleepMinutes)
        assertEquals(180, result.shallowSleepMinutes)
        assertEquals(100, result.deepSleepMinutes)
    }

    @Test
    fun zeroCurrentActivityDoesNotEraseDerivedSleep() {
        val accumulator = LenovoWatchSyncAccumulator(LenovoWatchHealthSnapshot())
        accumulator.accept(sleepSegment(LocalDate.of(2026, 7, 22), 23, type = 1, duration = 200))
        accumulator.accept(
            LenovoWatchEvent.CurrentActivity(
                steps = 1_799,
                calories = 32,
                shallowSleepMinutes = 0,
                deepSleepMinutes = 0,
                awakeCount = 0,
                exerciseSeconds = 0,
                exerciseCount = 0,
            ),
        )

        assertEquals(200, accumulator.result().totalSleepMinutes)
        assertEquals(200, accumulator.result().shallowSleepMinutes)
        assertEquals(0, accumulator.result().deepSleepMinutes)
    }

    private fun hourlyVitals(hour: Int, heartRate: Int, bloodOxygen: Int) = LenovoWatchEvent.HourlyVitals(
        recordedHour = LocalDateTime.of(2026, 7, 23, hour, 0),
        steps = 0,
        calories = 0,
        heartRate = heartRate,
        bloodOxygen = bloodOxygen,
        systolic = 0,
        diastolic = 0,
        pressure = 0,
        exerciseSeconds = null,
        exerciseCount = null,
    )

    private fun measurement(hour: Int, value: Double) = LenovoWatchEvent.Measurement(
        recordedAt = LocalDateTime.of(2026, 7, 23, hour, 0),
        kind = LenovoWatchMeasurementKind.HEART_RATE,
        primaryValue = value,
    )

    private fun sleepSegment(
        date: LocalDate,
        hour: Int,
        type: Int,
        duration: Int,
    ) = LenovoWatchEvent.SleepSegment(
        startedAt = date.atTime(hour, 0),
        rawType = type,
        durationMinutes = duration,
    )
}
