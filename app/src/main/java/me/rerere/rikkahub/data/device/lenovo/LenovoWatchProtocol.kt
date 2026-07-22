package me.rerere.rikkahub.data.device.lenovo

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Pure protocol primitives for Lenovo Watch Pro.
 *
 * This object deliberately does not expose the official client's disconnect/unbind command.
 * Pairing and writes must stay behind an explicit user action in the Android BLE layer.
 */
internal object LenovoWatchProtocol {
    val mainServiceUuid: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val writeCharacteristicUuid: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val notifyCharacteristicUuid: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    val fileServiceUuid: UUID = UUID.fromString("6e400011-b5a3-f393-e0a9-e50e24dcca9e")
    val fileWriteCharacteristicUuid: UUID = UUID.fromString("6e400012-b5a3-f393-e0a9-e50e24dcca9e")
    val fileNotifyCharacteristicUuid: UUID = UUID.fromString("6e400013-b5a3-f393-e0a9-e50e24dcca9e")

    enum class ConnectionKind(val wireValue: Int) {
        NEW(0),
        KNOWN(1),
    }

    fun phoneSystem(): ByteArray = hex("AB0005FF20800101")

    fun deviceInfo(): ByteArray = hex("AB0004FF928001")

    fun license(): ByteArray = hex("AB0002FFCB")

    fun serialNumber(): ByteArray = hex("AB0003FFAA80")

    fun heartbeatReply(): ByteArray = hex("EA0003000B02")

    fun foreground(): ByteArray = hex("EA000400110301")

    fun connectionRequest(
        phoneModel: String,
        uid: Long,
        kind: ConnectionKind,
    ): ByteArray {
        require(uid >= 0) { "uid must not be negative" }
        val modelBytes = phoneModel.toByteArray(StandardCharsets.UTF_8).takeAtMost(12)
        val uidBytes = uid.toString().toByteArray(StandardCharsets.UTF_8)
        require(uidBytes.size <= UByte.MAX_VALUE.toInt()) { "uid is too long" }

        return frame(
            type = 0xAB,
            payload = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x80.toByte(), kind.wireValue.toByte()) +
                byteArrayOf(modelBytes.size.toByte()) + modelBytes +
                byteArrayOf(uidBytes.size.toByte()) + uidBytes,
        )
    }

    /** Builds the official 0x51 history request. A null [since] means first sync. */
    fun healthSync(
        since: LocalDateTime?,
        currentDate: LocalDate = LocalDate.now(),
    ): ByteArray {
        val startDate = since?.toLocalDate() ?: currentDate
        val end = since
        return frame(
            type = 0xAB,
            payload = byteArrayOf(
                0xFF.toByte(),
                0x51,
                0x80.toByte(),
                0,
                startDate.wireYear(),
                startDate.monthValue.toByte(),
                startDate.dayOfMonth.toByte(),
                0,
                0,
                end?.year?.minus(2000)?.toByte() ?: 0,
                end?.monthValue?.toByte() ?: 0,
                end?.dayOfMonth?.toByte() ?: 0,
                end?.hour?.toByte() ?: 0,
                end?.minute?.toByte() ?: 0,
            ),
        )
    }

    fun sleepSync(since: LocalDate): ByteArray = frame(
        type = 0xAB,
        payload = byteArrayOf(
            0xFF.toByte(),
            0x52,
            0x80.toByte(),
            0,
            since.wireYear(),
            since.monthValue.toByte(),
            since.dayOfMonth.toByte(),
        ),
    )

    fun gpsSync(unixSeconds: Int = 0): ByteArray = frame(
        type = 0xEA,
        payload = byteArrayOf(0, 0x1C, 1) + int32BigEndian(unixSeconds),
    )

    fun frame(type: Int, payload: ByteArray): ByteArray {
        require(type in 0..UByte.MAX_VALUE.toInt()) { "invalid frame type" }
        require(payload.size <= UShort.MAX_VALUE.toInt()) { "payload is too large" }
        return byteArrayOf(
            type.toByte(),
            (payload.size ushr 8).toByte(),
            payload.size.toByte(),
        ) + payload
    }

    fun splitForBle(frame: ByteArray, mtuPayload: Int = 20): List<ByteArray> {
        require(mtuPayload >= 2) { "BLE payload must leave room for continuation sequence" }
        if (frame.size <= mtuPayload) return listOf(frame.copyOf())

        val chunks = mutableListOf(frame.copyOfRange(0, mtuPayload))
        val continuationPayload = mtuPayload - 1
        var offset = mtuPayload
        var sequence = 0
        while (offset < frame.size) {
            val end = minOf(offset + continuationPayload, frame.size)
            chunks += byteArrayOf(sequence.toByte()) + frame.copyOfRange(offset, end)
            offset = end
            sequence += 1
        }
        return chunks
    }

    fun declaredFrameSize(frameStart: ByteArray): Int? {
        if (frameStart.size < 3) return null
        return 3 + ((frameStart[1].toInt() and 0xFF) shl 8) + (frameStart[2].toInt() and 0xFF)
    }

    private fun LocalDate.wireYear(): Byte {
        require(year in 2000..2255) { "year is outside the watch protocol range" }
        return (year - 2000).toByte()
    }

    private fun ByteArray.takeAtMost(maxBytes: Int): ByteArray = copyOfRange(0, minOf(size, maxBytes))

    private fun int32BigEndian(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
