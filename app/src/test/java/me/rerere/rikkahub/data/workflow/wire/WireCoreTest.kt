package me.rerere.rikkahub.data.workflow.wire

import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WireCoreTest {
    private val crypto = WireCrypto()
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { (0xa0 + it).toByte() }
    private val plaintext = "知行 Wire v1".toByteArray()

    @Test
    fun `matches Node AAD and cipher bundle vector`() {
        assertEquals(AAD_BASE64URL, Base64.getUrlEncoder().withoutPadding().encodeToString(WireAad.encode(HEADER)))
        val encrypted = crypto.encryptBytes(plaintext, key, HEADER, nonce)
        assertEquals(CIPHER_BUNDLE, encrypted)
        assertArrayEquals(plaintext, crypto.decryptBytes(encrypted, key, HEADER))
    }

    @Test
    fun `authenticates every routing field`() {
        val encrypted = crypto.encryptBytes(plaintext, key, HEADER, nonce)
        listOf(
            HEADER.copy(id = "msg_02"),
            HEADER.copy(accountId = "acct_other"),
            HEADER.copy(senderDeviceId = "agent_other"),
            HEADER.copy(targetId = "device_other"),
            HEADER.copy(streamId = "thread_other"),
            HEADER.copy(seq = 2),
            HEADER.copy(createdAt = HEADER.createdAt + 1),
            HEADER.copy(expiresAt = HEADER.createdAt + 1000),
            HEADER.copy(keyId = "key_other"),
        ).forEach { changed ->
            assertThrows(WireProtocolException::class.java) {
                crypto.decryptBytes(encrypted, key, changed)
            }
        }
    }

    @Test
    fun `matches Node domain separation and wrapped data key vector`() {
        val rootSecret = ByteArray(32) { it.toByte() }
        val contentSecret = WireKeys.deriveContentSecretKey(rootSecret)
        assertFalse(WireKeys.deriveAuthSeed(rootSecret).contentEquals(contentSecret))
        assertEquals(
            CONTENT_PUBLIC_KEY,
            Base64.getUrlEncoder().withoutPadding().encodeToString(WireKeys.deriveContentPublicKey(rootSecret)),
        )
        assertArrayEquals(
            ByteArray(32) { (0x20 + it).toByte() },
            WireKeys.unwrapDataKey(WRAPPED_DATA_KEY, contentSecret),
        )
        assertEquals(null, WireKeys.unwrapDataKey(WRAPPED_DATA_KEY, ByteArray(32) { 7 }))
    }

    @Test
    fun `blocks incompatible writes and applies ordered state safely`() {
        val hello = WireHello(1, 0, "android", "0.2.0", "phone_test", setOf("catalog.v1"))
        hello.requireWritable(setOf("catalog.v1"))
        assertThrows(WireProtocolException::class.java) { hello.copy(wireMajor = 2).requireWritable(emptySet()) }
        assertThrows(WireProtocolException::class.java) { hello.requireWritable(setOf("runtime.v1")) }

        val tracker = WireSequenceTracker(maxReorderWindow = 4)
        val second = envelope(2, "msg_02")
        assertEquals(SequenceDecision.Buffered, tracker.accept(second, HEADER.createdAt))
        assertEquals(SequenceDecision.Duplicate, tracker.accept(second, HEADER.createdAt))
        assertEquals(
            SequenceDecision.Collision,
            tracker.accept(second.copy(cipherBundle = "different"), HEADER.createdAt),
        )
        assertEquals(
            SequenceDecision.Applied(listOf(1, 2)),
            tracker.accept(envelope(1, "msg_01"), HEADER.createdAt),
        )
        assertEquals(SequenceDecision.Gap(3), tracker.accept(envelope(7, "msg_07"), HEADER.createdAt))
        assertEquals(
            SequenceDecision.Expired,
            tracker.accept(envelope(3, "msg_03").copy(header = HEADER.copy(seq = 3, id = "msg_03", expiresAt = HEADER.createdAt)), HEADER.createdAt),
        )
    }

    @Test
    fun `preserves unknown optional envelope fields`() {
        val encoded = """
            {"v":1,"id":"msg_01","accountId":"acct_test","senderDeviceId":"agent_test","targetId":"account_test","streamId":"thread_test","seq":1,"createdAt":1784397723000,"expiresAt":null,"keyId":"key_test","cipherBundle":"AQ","futureField":"kept"}
        """.trimIndent()
        val decoded = WireEnvelopeCodec.decode(encoded)

        assertEquals(JsonPrimitive("kept"), decoded.extensions["futureField"])
        assertEquals(decoded, WireEnvelopeCodec.decode(WireEnvelopeCodec.encode(decoded)))
    }

    @Test
    fun `rejects revision gaps and tombstone resurrection`() {
        val guard = WireRevisionGuard(3)
        assertFalse(guard.applyDelta(2, 4))
        assertTrue(guard.applyDelta(3, 4))
        assertFalse(guard.applySnapshot(4))
        assertTrue(guard.recordTombstone("machine_1", "thread_1", 5))
        assertFalse(guard.canUpsert("machine_1", "thread_1"))
        assertFalse(guard.recordTombstone("machine_1", "thread_1", 4))
        assertFalse(guard.recordTombstone("machine_1", "thread_2", 5))
    }

    private fun envelope(seq: Long, id: String) = WireEnvelope(
        header = HEADER.copy(seq = seq, id = id),
        cipherBundle = "bundle_$seq",
    )

    private companion object {
        val HEADER = WireEnvelopeHeader(
            v = 1,
            id = "msg_01",
            accountId = "acct_test",
            senderDeviceId = "agent_test",
            targetId = "account_test",
            streamId = "thread_test",
            seq = 1,
            createdAt = 1_784_397_723_000,
            expiresAt = null,
            keyId = "key_test",
        )
        const val AAD_BASE64URL =
            "WlhXMQAGbXNnXzAxAAlhY2N0X3Rlc3QACmFnZW50X3Rlc3QADGFjY291bnRfdGVzdAALdGhyZWFkX3Rlc3QAAAAAAAAAAQAAAZ92ZHV4__________8ACGtleV90ZXN0"
        const val CIPHER_BUNDLE = "AaChoqOkpaanqKmqqwGH2cXkRyLoCxfi83FLEOWrPpP4AFrAfuDjvCjh0g"
        const val WRAPPED_DATA_KEY =
            "AXmmMe7eG_nJjxIDLN6t0OegeTmPx4a4jMhG7ImvhaUaAAECAwQFBgcICQoLDA0ODxAREhMUFRYX3z-ibFpVd4Oi57p4EWTfvGdUtYwIlWzeO_ZDnlLIYq4FhvxyQyl4W-9ncDF8IUcc"
        const val CONTENT_PUBLIC_KEY = "fJh-G2jIE8keOaEOwOsL19cU_zrmRYv_Tfv_2vCgaFI"
    }
}
