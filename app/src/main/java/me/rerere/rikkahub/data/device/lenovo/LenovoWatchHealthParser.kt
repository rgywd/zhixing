package me.rerere.rikkahub.data.device.lenovo

import java.time.DateTimeException
import java.time.LocalDateTime

internal sealed interface LenovoWatchEvent {
    data class PairingCapabilities(val bytes: List<Int>) : LenovoWatchEvent

    data class ConnectionResult(val state: LenovoWatchConnectionState) : LenovoWatchEvent

    data class CurrentActivity(
        val steps: Int,
        val calories: Int,
        val shallowSleepMinutes: Int,
        val deepSleepMinutes: Int,
        val awakeCount: Int,
        val exerciseSeconds: Int?,
        val exerciseCount: Int?,
    ) : LenovoWatchEvent

    data class HourlyVitals(
        val recordedHour: LocalDateTime,
        val steps: Int,
        val calories: Int,
        val heartRate: Int,
        val bloodOxygen: Int,
        val systolic: Int,
        val diastolic: Int,
        val pressure: Int,
        val exerciseSeconds: Int?,
        val exerciseCount: Int?,
    ) : LenovoWatchEvent

    data class HourlyRecovery(
        val recordedHour: LocalDateTime,
        val immunity: Int,
        val temperatureCelsius: Double,
    ) : LenovoWatchEvent

    data class SleepSegment(
        val startedAt: LocalDateTime,
        val rawType: Int,
        val durationMinutes: Int,
    ) : LenovoWatchEvent

    data class Measurement(
        val recordedAt: LocalDateTime,
        val kind: LenovoWatchMeasurementKind,
        val primaryValue: Double,
        val secondaryValue: Double? = null,
    ) : LenovoWatchEvent

    data class InstantMeasurement(
        val kind: LenovoWatchMeasurementKind,
        val primaryValue: Double,
        val secondaryValue: Double? = null,
    ) : LenovoWatchEvent

    data class OneKeyMeasurement(
        val heartRate: Int,
        val bloodOxygen: Int,
        val systolic: Int,
        val diastolic: Int,
    ) : LenovoWatchEvent

    data class Unknown(val frameType: Int, val command: Int?, val subcommand: Int?) : LenovoWatchEvent

    data class Malformed(val reason: String) : LenovoWatchEvent
}

internal enum class LenovoWatchConnectionState {
    ACCEPTED,
    REJECTED,
    TIMED_OUT,
    WATCH_UNBOUND,
    ACCOUNT_MISMATCH,
    UNKNOWN,
}

internal enum class LenovoWatchMeasurementKind {
    HEART_RATE,
    BLOOD_OXYGEN,
    BLOOD_PRESSURE,
    TEMPERATURE,
    IMMUNITY,
    PRESSURE,
}

internal object LenovoWatchHealthParser {
    fun parse(frame: ByteArray): LenovoWatchEvent {
        if (frame.size < 5) return LenovoWatchEvent.Malformed("frame is shorter than command header")
        val declaredSize = LenovoWatchProtocol.declaredFrameSize(frame)
            ?: return LenovoWatchEvent.Malformed("missing frame length")
        if (declaredSize != frame.size) {
            return LenovoWatchEvent.Malformed("declared $declaredSize bytes but received ${frame.size}")
        }

        val type = frame.u8(0)
        val command = frame.u8(4)
        return when {
            type != 0xAB -> LenovoWatchEvent.Unknown(type, command, frame.u8OrNull(5))
            command == 0x20 -> parseCapabilities(frame)
            command == 0xE0 -> parseConnectionResult(frame)
            command == 0x31 -> parseInstantMeasurement(frame)
            command == 0x32 -> parseOnceMeasurement(frame)
            command == 0x51 -> parseHealth(frame)
            command == 0x52 -> parseSleep(frame)
            else -> LenovoWatchEvent.Unknown(type, command, frame.u8OrNull(5))
        }
    }

    private fun parseCapabilities(frame: ByteArray): LenovoWatchEvent {
        if (frame.size < 8) return LenovoWatchEvent.Malformed("pairing capabilities are truncated")
        return LenovoWatchEvent.PairingCapabilities(frame.copyOfRange(6, frame.size).map { it.toInt() and 0xFF })
    }

    private fun parseConnectionResult(frame: ByteArray): LenovoWatchEvent {
        if (frame.size < 7) return LenovoWatchEvent.Malformed("connection result is truncated")
        val state = when (frame.u8(6)) {
            0 -> LenovoWatchConnectionState.ACCEPTED
            1 -> LenovoWatchConnectionState.REJECTED
            2 -> LenovoWatchConnectionState.TIMED_OUT
            3 -> LenovoWatchConnectionState.WATCH_UNBOUND
            4 -> LenovoWatchConnectionState.ACCOUNT_MISMATCH
            else -> LenovoWatchConnectionState.UNKNOWN
        }
        return LenovoWatchEvent.ConnectionResult(state)
    }

    private fun parseHealth(frame: ByteArray): LenovoWatchEvent {
        if (frame.size < 6) return LenovoWatchEvent.Malformed("health frame has no subcommand")
        return when (frame.u8(5)) {
            0x08 -> parseCurrentActivity(frame)
            0x0B -> parseDatedMeasurement(frame, LenovoWatchMeasurementKind.PRESSURE)
            0x11 -> parseDatedMeasurement(frame, LenovoWatchMeasurementKind.HEART_RATE)
            0x12 -> parseDatedMeasurement(frame, LenovoWatchMeasurementKind.BLOOD_OXYGEN)
            0x13 -> parseDatedMeasurement(frame, LenovoWatchMeasurementKind.TEMPERATURE)
            0x14 -> parseDatedMeasurement(frame, LenovoWatchMeasurementKind.BLOOD_PRESSURE)
            0x18 -> parseDatedMeasurement(frame, LenovoWatchMeasurementKind.IMMUNITY)
            0x20 -> parseHourlyVitals(frame)
            0x21 -> parseHourlyRecovery(frame)
            else -> LenovoWatchEvent.Unknown(frame.u8(0), frame.u8(4), frame.u8(5))
        }
    }

    private fun parseCurrentActivity(frame: ByteArray): LenovoWatchEvent {
        if (frame.size < 17) return LenovoWatchEvent.Malformed("current activity frame is truncated")
        return LenovoWatchEvent.CurrentActivity(
            steps = frame.u24(6),
            calories = frame.u24(9),
            shallowSleepMinutes = frame.u8(12) * 60 + frame.u8(13),
            deepSleepMinutes = frame.u8(14) * 60 + frame.u8(15),
            awakeCount = frame.u8(16),
            exerciseSeconds = if (frame.size >= 19) frame.u16(17) else null,
            exerciseCount = frame.u8OrNull(19),
        )
    }

    private fun parseHourlyVitals(frame: ByteArray): LenovoWatchEvent {
        if (frame.size < 20) return LenovoWatchEvent.Malformed("hourly vitals frame is truncated")
        val timestamp = frame.dateTime(hourIndex = 9, minuteIndex = null)
            ?: return LenovoWatchEvent.Malformed("hourly vitals contain an invalid timestamp")
        val heartRate = frame.u8(16)
        return LenovoWatchEvent.HourlyVitals(
            recordedHour = timestamp,
            steps = frame.u24(10),
            calories = frame.u24(13),
            heartRate = heartRate,
            bloodOxygen = frame.u8(17),
            systolic = frame.u8(18),
            diastolic = frame.u8(19),
            pressure = when {
                heartRate > 139 -> 99
                heartRate < 40 -> 0
                heartRate <= 79 -> heartRate - 30
                else -> heartRate - 40
            },
            exerciseSeconds = if (frame.size >= 27) frame.u16(25) else null,
            exerciseCount = frame.u8OrNull(27),
        )
    }

    private fun parseHourlyRecovery(frame: ByteArray): LenovoWatchEvent {
        if (frame.size < 13) return LenovoWatchEvent.Malformed("hourly recovery frame is truncated")
        val timestamp = frame.dateTime(hourIndex = 9, minuteIndex = null)
            ?: return LenovoWatchEvent.Malformed("hourly recovery contains an invalid timestamp")
        val temperature = decimal(frame.u8(11), frame.u8(12)).let { if (it >= 50.0) 36.5 else it }
        return LenovoWatchEvent.HourlyRecovery(
            recordedHour = timestamp,
            immunity = minOf(frame.u8(10), 100),
            temperatureCelsius = temperature,
        )
    }

    private fun parseSleep(frame: ByteArray): LenovoWatchEvent {
        if (frame.size < 14) return LenovoWatchEvent.Malformed("sleep frame is truncated")
        val timestamp = frame.dateTime(hourIndex = 9, minuteIndex = 10)
            ?: return LenovoWatchEvent.Malformed("sleep frame contains an invalid timestamp")
        return LenovoWatchEvent.SleepSegment(
            startedAt = timestamp,
            rawType = frame.u8(11),
            durationMinutes = frame.u16(12),
        )
    }

    private fun parseDatedMeasurement(
        frame: ByteArray,
        kind: LenovoWatchMeasurementKind,
    ): LenovoWatchEvent {
        val requiredSize = if (kind == LenovoWatchMeasurementKind.BLOOD_PRESSURE || kind == LenovoWatchMeasurementKind.TEMPERATURE) 13 else 12
        if (frame.size < requiredSize) return LenovoWatchEvent.Malformed("$kind measurement is truncated")
        val timestamp = frame.dateTime(hourIndex = 9, minuteIndex = 10)
            ?: return LenovoWatchEvent.Malformed("$kind measurement contains an invalid timestamp")
        val (primary, secondary) = measurementValues(frame, kind, 11)
        return LenovoWatchEvent.Measurement(timestamp, kind, primary, secondary)
    }

    private fun parseInstantMeasurement(frame: ByteArray): LenovoWatchEvent {
        if (frame.size < 7) return LenovoWatchEvent.Malformed("instant measurement is truncated")
        val kind = when (frame.u8(5)) {
            0x09, 0x0A -> LenovoWatchMeasurementKind.HEART_RATE
            0x11, 0x12 -> LenovoWatchMeasurementKind.BLOOD_OXYGEN
            0x21, 0x22 -> LenovoWatchMeasurementKind.BLOOD_PRESSURE
            0x41 -> LenovoWatchMeasurementKind.IMMUNITY
            0x81 -> LenovoWatchMeasurementKind.TEMPERATURE
            else -> return LenovoWatchEvent.Unknown(frame.u8(0), frame.u8(4), frame.u8(5))
        }
        val needed = if (kind == LenovoWatchMeasurementKind.BLOOD_PRESSURE || kind == LenovoWatchMeasurementKind.TEMPERATURE) 8 else 7
        if (frame.size < needed) return LenovoWatchEvent.Malformed("$kind instant measurement is truncated")
        val (primary, secondary) = measurementValues(frame, kind, 6)
        return LenovoWatchEvent.InstantMeasurement(kind, primary, secondary)
    }

    private fun parseOnceMeasurement(frame: ByteArray): LenovoWatchEvent {
        if (frame.size < 10) return LenovoWatchEvent.Malformed("one-key measurement is truncated")
        return LenovoWatchEvent.OneKeyMeasurement(
            heartRate = frame.u8(6),
            bloodOxygen = frame.u8(7),
            systolic = frame.u8(8),
            diastolic = frame.u8(9),
        )
    }

    private fun measurementValues(
        frame: ByteArray,
        kind: LenovoWatchMeasurementKind,
        valueIndex: Int,
    ): Pair<Double, Double?> = when (kind) {
        LenovoWatchMeasurementKind.BLOOD_PRESSURE -> frame.u8(valueIndex).toDouble() to frame.u8(valueIndex + 1).toDouble()
        LenovoWatchMeasurementKind.TEMPERATURE -> decimal(frame.u8(valueIndex), frame.u8(valueIndex + 1)) to null
        else -> frame.u8(valueIndex).toDouble() to null
    }

    private fun ByteArray.dateTime(hourIndex: Int, minuteIndex: Int?): LocalDateTime? = try {
        LocalDateTime.of(
            u8(6) + 2000,
            u8(7),
            u8(8),
            u8(hourIndex),
            minuteIndex?.let { u8(it) } ?: 0,
        )
    } catch (_: DateTimeException) {
        null
    }

    private fun decimal(integer: Int, fraction: Int): Double = "$integer.$fraction".toDouble()

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.u8OrNull(index: Int): Int? = getOrNull(index)?.toInt()?.and(0xFF)

    private fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

    private fun ByteArray.u24(index: Int): Int = (u8(index) shl 16) or (u8(index + 1) shl 8) or u8(index + 2)
}
