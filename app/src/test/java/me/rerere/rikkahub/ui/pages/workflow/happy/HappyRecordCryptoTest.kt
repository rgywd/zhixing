package me.rerere.rikkahub.ui.pages.workflow.happy

import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HappyRecordCryptoTest {
    private val crypto = HappyRecordCrypto(Json)
    private val accountSecret = ByteArray(32) { it.toByte() }

    @Test
    fun `unwraps Happy data key and decrypts AES GCM metadata vector`() {
        val dataKey = crypto.unwrapDataKey(WRAPPED_DATA_KEY, accountSecret)

        assertNotNull(dataKey)
        assertEquals(
            (32 until 64).joinToString("") { "%02x".format(it) },
            dataKey!!.joinToString("") { "%02x".format(it) },
        )

        val metadata = crypto.decryptJson(MACHINE_METADATA, dataKey)
        assertEquals("devbox", metadata?.get("host")?.toString()?.trim('"'))
        assertEquals("Dev Box", metadata?.get("displayName")?.toString()?.trim('"'))
    }

    @Test
    fun `rejects wrapped key with unsupported version`() {
        val unsupported = Base64.getDecoder().decode(WRAPPED_DATA_KEY).also { it[0] = 1 }

        assertEquals(
            null,
            crypto.unwrapDataKey(Base64.getEncoder().encodeToString(unsupported), accountSecret),
        )
    }

    private companion object {
        const val WRAPPED_DATA_KEY =
            "AHmmMe7eG/nJjxIDLN6t0OegeTmPx4a4jMhG7ImvhaUaAAECAwQFBgcICQoLDA0ODxAREhMUFRYXAnP4cVTw5/gxKEWVRSBPNnuzvf7c6T8c+sQc4OvwP250mYVcNotn0VM/ngswlv89"
        const val MACHINE_METADATA =
            "AAABAgMEBQYHCAkKCydwNcg5QcnWnp2ssEQcJh4/UpkIC2XAflZg8YCZ9qL2H+wwETl3JZWaArmUpZ1Pw0FggpB4adW0iA8imgeRQcckt/tjvrLRvBcI5FFjW7ivqGjs17JufW8OrGkkrTGaMTdfe6AHCiPgmx+vk8YlCyjq0lMF+n8aHP3lhGkmBro6cvT3XHn7TfW50nbLR6F0wx6ElTquT5ttWsIdiH/Y9L7xtx18aIg="
    }
}
