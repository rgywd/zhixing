package me.rerere.tts.provider.providers

import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VolcengineTTSProtocolTest {
    @Test
    fun requestUsesV3BinaryHeaderAndConfiguredVoice() {
        val frame = encodeVolcengineTtsRequest(TTSProviderSetting.Volcengine(), "你好")

        assertArrayEquals(byteArrayOf(0x11, 0x10, 0x10, 0x00), frame.copyOfRange(0, 4))
        val payloadLength = ByteBuffer.wrap(frame, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val payload = frame.copyOfRange(8, frame.size).decodeToString()
        assertEquals(payloadLength, frame.size - 8)
        assertTrue(payload.contains("你好"))
        assertTrue(payload.contains("zh_female_xiaohe_uranus_bigtts"))
        assertTrue(payload.contains("\"sample_rate\":24000"))
    }

    @Test
    fun parsesAudioOnlyFrame() {
        val audio = byteArrayOf(1, 2, 3, 4)
        val frame = serverFrame(type = 0xb, payload = audio)

        val parsed = parseVolcengineTtsMessage(frame) as VolcengineServerMessage.Audio

        assertArrayEquals(audio, parsed.data)
    }

    @Test
    fun parsesSessionFinishedFrame() {
        val frame = serverFrame(type = 0x9, event = 152, payload = "{}".encodeToByteArray())

        assertEquals(VolcengineServerMessage.Finished, parseVolcengineTtsMessage(frame))
    }

    @Test
    fun surfacesProtocolError() {
        val message = "invalid api key".encodeToByteArray()
        val buffer = ByteBuffer.allocate(12 + message.size).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x11, 0xf0.toByte(), 0x10, 0x00))
            .putInt(45000000)
            .putInt(message.size)
            .put(message)
            .array()

        val error = parseVolcengineTtsMessage(buffer) as VolcengineServerMessage.Error

        assertTrue(error.message.contains("45000000"))
        assertTrue(error.message.contains("invalid api key"))
    }

    private fun serverFrame(type: Int, event: Int? = null, payload: ByteArray): ByteArray {
        val sessionId = byteArrayOf()
        val size = 4 + (if (event != null) 8 + sessionId.size else 0) + 4 + payload.size
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            .put(0x11)
            .put(((type shl 4) or if (event != null) 0x4 else 0).toByte())
            .put(0x10)
            .put(0x00)
            .apply {
                if (event != null) {
                    putInt(event)
                    putInt(sessionId.size)
                    put(sessionId)
                }
                putInt(payload.size)
                put(payload)
            }
            .array()
    }
}
