package me.rerere.rikkahub.ui.pages.workflow.happy

import com.iwebpp.crypto.TweetNaclFast
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HappyAuthApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `recovery key exchanges signed challenge for token`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"token\":\"happy-token\"}"))
        val secret = ByteArray(32) { (it + 5).toByte() }
        val api = HappyAuthApi(
            client = OkHttpClient(),
            json = Json,
            serverUrl = server.url("/").toString(),
            clientId = "android/test",
        )

        val credentials = api.exchangeRecoveryKey(HappySecretKeyCodec.formatForBackup(secret))
        val request = server.takeRequest()
        val payload = Json.parseToJsonElement(requireNotNull(request.body.readUtf8())).jsonObject
        val challenge = Base64.getDecoder().decode(payload.getValue("challenge").jsonPrimitive.content)
        val signature = Base64.getDecoder().decode(payload.getValue("signature").jsonPrimitive.content)
        val publicKey = Base64.getDecoder().decode(payload.getValue("publicKey").jsonPrimitive.content)

        assertEquals("POST", request.method)
        assertEquals("/v1/auth", request.path)
        assertEquals("android/test", request.getHeader("X-Happy-Client"))
        assertTrue(TweetNaclFast.Signature(publicKey, ByteArray(64)).detached_verify(challenge, signature))
        assertEquals("happy-token", credentials.token)
        assertEquals(HappySecretKeyCodec.encodeBase64Url(secret), credentials.secret)
        assertEquals(server.url("/").toString().trimEnd('/'), credentials.serverUrl)
    }
}
