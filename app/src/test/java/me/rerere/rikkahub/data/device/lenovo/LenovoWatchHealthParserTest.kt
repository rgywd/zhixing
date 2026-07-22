package me.rerere.rikkahub.data.device.lenovo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LenovoWatchHealthParserTest {
    @Test
    fun parsesHourlyVitals() {
        val payload = byteArrayOf(
            0xFF.toByte(), 0x51, 0x20,
            26, 7, 22, 15,
            0, 0x20, 0x96.toByte(),
            0, 0x01, 0xE6.toByte(),
            64, 97, 120, 80,
            0, 0, 0, 0, 0,
            0, 58, 2,
        )
        val event = LenovoWatchHealthParser.parse(LenovoWatchProtocol.frame(0xAB, payload))

        assertTrue(event is LenovoWatchEvent.HourlyVitals)
        event as LenovoWatchEvent.HourlyVitals
        assertEquals(LocalDateTime.of(2026, 7, 22, 15, 0), event.recordedHour)
        assertEquals(8342, event.steps)
        assertEquals(486, event.calories)
        assertEquals(64, event.heartRate)
        assertEquals(34, event.pressure)
        assertEquals(58, event.exerciseSeconds)
        assertEquals(2, event.exerciseCount)
    }

    @Test
    fun parsesRecoverySleepAndMeasuredValues() {
        val recovery = LenovoWatchHealthParser.parse(
            LenovoWatchProtocol.frame(
                0xAB,
                byteArrayOf(0xFF.toByte(), 0x51, 0x21, 26, 7, 22, 15, 83.toByte(), 36, 5),
            ),
        ) as LenovoWatchEvent.HourlyRecovery
        assertEquals(83, recovery.immunity)
        assertEquals(36.5, recovery.temperatureCelsius, 0.0)

        val sleep = LenovoWatchHealthParser.parse(
            LenovoWatchProtocol.frame(
                0xAB,
                byteArrayOf(0xFF.toByte(), 0x52, 0x00, 26, 7, 22, 1, 30, 2, 0, 90),
            ),
        ) as LenovoWatchEvent.SleepSegment
        assertEquals(LocalDateTime.of(2026, 7, 22, 1, 30), sleep.startedAt)
        assertEquals(90, sleep.durationMinutes)

        val bloodPressure = LenovoWatchHealthParser.parse(
            LenovoWatchProtocol.frame(
                0xAB,
                byteArrayOf(0xFF.toByte(), 0x51, 0x14, 26, 7, 22, 15, 42, 120, 80),
            ),
        ) as LenovoWatchEvent.Measurement
        assertEquals(LenovoWatchMeasurementKind.BLOOD_PRESSURE, bloodPressure.kind)
        assertEquals(120.0, bloodPressure.primaryValue, 0.0)
        assertEquals(80.0, bloodPressure.secondaryValue!!, 0.0)
    }

    @Test
    fun parsesCurrentActivityAndConnectionResult() {
        val activity = LenovoWatchHealthParser.parse(
            LenovoWatchProtocol.frame(
                0xAB,
                byteArrayOf(
                    0xFF.toByte(), 0x51, 0x08,
                    0, 0x20, 0x96.toByte(),
                    0, 1, 0xE6.toByte(),
                    5, 30, 1, 54, 2,
                    0, 58, 2,
                ),
            ),
        ) as LenovoWatchEvent.CurrentActivity
        assertEquals(8342, activity.steps)
        assertEquals(486, activity.calories)
        assertEquals(330, activity.shallowSleepMinutes)
        assertEquals(114, activity.deepSleepMinutes)

        val mismatch = LenovoWatchHealthParser.parse(
            LenovoWatchProtocol.frame(0xAB, byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x80.toByte(), 4)),
        )
        assertEquals(
            LenovoWatchEvent.ConnectionResult(LenovoWatchConnectionState.ACCOUNT_MISMATCH),
            mismatch,
        )
    }

    @Test
    fun parsesOneKeyMeasurementAsFourIndependentValues() {
        val event = LenovoWatchHealthParser.parse(
            LenovoWatchProtocol.frame(
                0xAB,
                byteArrayOf(0xFF.toByte(), 0x32, 0x00, 68, 98, 121, 79),
            ),
        ) as LenovoWatchEvent.OneKeyMeasurement

        assertEquals(68, event.heartRate)
        assertEquals(98, event.bloodOxygen)
        assertEquals(121, event.systolic)
        assertEquals(79, event.diastolic)
    }

    @Test
    fun rejectsTruncatedOrInvalidFramesWithoutThrowing() {
        assertTrue(LenovoWatchHealthParser.parse(byteArrayOf(0xAB.toByte(), 0, 1)) is LenovoWatchEvent.Malformed)
        val invalidDate = LenovoWatchProtocol.frame(
            0xAB,
            byteArrayOf(0xFF.toByte(), 0x51, 0x21, 26, 13, 40, 30, 80, 36, 5),
        )
        assertTrue(LenovoWatchHealthParser.parse(invalidDate) is LenovoWatchEvent.Malformed)
    }
}
