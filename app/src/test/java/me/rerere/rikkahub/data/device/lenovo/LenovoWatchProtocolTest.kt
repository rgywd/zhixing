package me.rerere.rikkahub.data.device.lenovo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LenovoWatchProtocolTest {
    @Test
    fun buildsConfirmedBootstrapCommands() {
        assertHex("AB0005FF20800101", LenovoWatchProtocol.phoneSystem())
        assertHex("AB0004FF928001", LenovoWatchProtocol.deviceInfo())
        assertHex("AB0002FFCB", LenovoWatchProtocol.license())
        assertHex("AB0003FFAA80", LenovoWatchProtocol.serialNumber())
        assertHex("EA0003000B02", LenovoWatchProtocol.heartbeatReply())
        assertHex("EA000400110301", LenovoWatchProtocol.foreground())
    }

    @Test
    fun buildsFirstAndIncrementalHealthSync() {
        assertHex(
            "AB000EFF5180001A071600000000000000",
            LenovoWatchProtocol.healthSync(null, LocalDate.of(2026, 7, 22)),
        )
        assertHex(
            "AB000EFF5180001A071600001A07160F2A",
            LenovoWatchProtocol.healthSync(LocalDateTime.of(2026, 7, 22, 15, 42)),
        )
        assertHex("AB0007FF5280001A0716", LenovoWatchProtocol.sleepSync(LocalDate.of(2026, 7, 22)))
        assertHex("EA0007001C0112345678", LenovoWatchProtocol.gpsSync(0x12345678))
    }

    @Test
    fun connectionRequestUsesOfficialRawByteTruncationAndBleChunks() {
        val frame = LenovoWatchProtocol.connectionRequest(
            phoneModel = "123456789012345",
            uid = 123456,
            kind = LenovoWatchProtocol.ConnectionKind.NEW,
        )
        assertHex("AB0018FFE080000C31323334353637383930313206313233343536", frame)

        val chunks = LenovoWatchProtocol.splitForBle(frame)
        assertEquals(2, chunks.size)
        assertEquals(20, chunks[0].size)
        assertEquals(8, chunks[1].size)
        assertEquals(0, chunks[1][0].toInt())
    }

    @Test
    fun packetAssemblerReconstructsAndValidatesContinuationSequence() {
        val frame = LenovoWatchProtocol.connectionRequest(
            phoneModel = "Pixel 10 Pro XL",
            uid = 123456789,
            kind = LenovoWatchProtocol.ConnectionKind.KNOWN,
        )
        val chunks = LenovoWatchProtocol.splitForBle(frame)
        val assembler = LenovoWatchPacketAssembler()

        assertEquals(LenovoWatchPacketAssembly.Incomplete, assembler.accept(chunks.first()))
        val result = assembler.accept(chunks[1])
        assertTrue(result is LenovoWatchPacketAssembly.Complete)
        assertArrayEquals(frame, (result as LenovoWatchPacketAssembly.Complete).frame)

        assertEquals(LenovoWatchPacketAssembly.Incomplete, assembler.accept(chunks.first()))
        val rejected = assembler.accept(chunks[1].copyOf().also { it[0] = 3 })
        assertTrue(rejected is LenovoWatchPacketAssembly.Rejected)
    }

    private fun assertHex(expected: String, actual: ByteArray) {
        assertArrayEquals(expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), actual)
    }
}
