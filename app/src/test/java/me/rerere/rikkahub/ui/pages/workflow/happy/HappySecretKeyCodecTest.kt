package me.rerere.rikkahub.ui.pages.workflow.happy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HappySecretKeyCodecTest {
    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun `backup format and base64url both round trip`() {
        val formatted = HappySecretKeyCodec.formatForBackup(secret)
        val base64Url = HappySecretKeyCodec.encodeBase64Url(secret)

        assertEquals(11, formatted.split('-').size)
        assertArrayEquals(secret, HappySecretKeyCodec.decode(formatted))
        assertArrayEquals(secret, HappySecretKeyCodec.decode(base64Url))
    }

    @Test
    fun `invalid key length is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            HappySecretKeyCodec.decode("AAAAA-AAAAA")
        }
    }
}
