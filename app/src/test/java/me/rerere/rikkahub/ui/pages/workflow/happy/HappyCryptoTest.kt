package me.rerere.rikkahub.ui.pages.workflow.happy

import com.iwebpp.crypto.TweetNaclFast
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HappyCryptoTest {
    @Test
    fun `auth challenge is signed by account seed`() {
        val secret = ByteArray(32) { (it + 1).toByte() }
        val challenge = ByteArray(32) { (it + 33).toByte() }

        val result = HappyCrypto().createAuthChallenge(secret, challenge)
        val verifier = TweetNaclFast.Signature(result.publicKey, ByteArray(64))

        assertArrayEquals(challenge, result.challenge)
        assertEquals(32, result.publicKey.size)
        assertEquals(64, result.signature.size)
        assertTrue(verifier.detached_verify(challenge, result.signature))
    }
}
