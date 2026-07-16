package me.rerere.rikkahub.ui.pages.workflow.happy

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HappySyncApiTest {
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
    fun `snapshot keeps machines and decrypts their metadata`() = runBlocking {
        server.enqueue(MockResponse().setBody(MACHINES_RESPONSE))
        server.enqueue(MockResponse().setBody("{\"sessions\":[]}"))
        val api = HappySyncApi(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            serverUrl = server.url("/").toString(),
            clientId = "android/test",
        )
        val credentials = HappyCredentials(
            token = "happy-token",
            secret = HappySecretKeyCodec.encodeBase64Url(ByteArray(32) { it.toByte() }),
        )

        val snapshot = api.fetchSnapshot(credentials)
        val machine = snapshot.machines.single()
        val machineRequest = server.takeRequest()
        val sessionRequest = server.takeRequest()

        assertEquals("devbox", machine.host)
        assertEquals("Dev Box", machine.displayName)
        assertTrue(machine.active)
        assertNull(machine.supportsCodex)
        assertEquals("/v1/machines", machineRequest.path)
        assertEquals("/v2/sessions/active?limit=150", sessionRequest.path)
        assertEquals("Bearer happy-token", machineRequest.getHeader("Authorization"))
        assertEquals("android/test", machineRequest.getHeader("X-Happy-Client"))
    }

    private companion object {
        const val MACHINES_RESPONSE = """
            [{
              "id":"machine-1",
              "metadata":"AAABAgMEBQYHCAkKCydwNcg5QcnWnp2ssEQcJh4/UpkIC2XAflZg8YCZ9qL2H+wwETl3JZWaArmUpZ1Pw0FggpB4adW0iA8imgeRQcckt/tjvrLRvBcI5FFjW7ivqGjs17JufW8OrGkkrTGaMTdfe6AHCiPgmx+vk8YlCyjq0lMF+n8aHP3lhGkmBro6cvT3XHn7TfW50nbLR6F0wx6ElTquT5ttWsIdiH/Y9L7xtx18aIg=",
              "metadataVersion":1,
              "daemonState":null,
              "daemonStateVersion":0,
              "dataEncryptionKey":"AHmmMe7eG/nJjxIDLN6t0OegeTmPx4a4jMhG7ImvhaUaAAECAwQFBgcICQoLDA0ODxAREhMUFRYXAnP4cVTw5/gxKEWVRSBPNnuzvf7c6T8c+sQc4OvwP250mYVcNotn0VM/ngswlv89",
              "seq":3,
              "active":true,
              "activeAt":1720000000000,
              "createdAt":1710000000000,
              "updatedAt":1720000000000
            }]
        """
    }
}
