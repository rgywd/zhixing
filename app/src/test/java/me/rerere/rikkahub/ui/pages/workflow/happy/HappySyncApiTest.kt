package me.rerere.rikkahub.ui.pages.workflow.happy

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `reads encrypted history incrementally and sends encrypted user message`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val key = ByteArray(32) { (it + 32).toByte() }
        val crypto = HappyRecordCrypto(json)
        val encryptedAgentMessage = crypto.encryptElement(
            json.parseToJsonElement(
                """{"role":"agent","content":{"type":"text","data":{"type":"message","message":"闭环完成"}}}"""
            ).jsonObject,
            key,
            HappyEncryptionVariant.DATA_KEY,
        )
        val encryptedSessionMessage = crypto.encryptElement(
            json.parseToJsonElement(
                """{"role":"session","content":{"id":"event-10","time":1720000000001,"role":"agent","ev":{"t":"tool-call-start","call":"call-1","name":"CodexBash","title":"运行测试","description":"执行闭环检查","args":{}}}}"""
            ).jsonObject,
            key,
            HappyEncryptionVariant.DATA_KEY,
        )
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[{"id":"message-9","seq":9,"localId":null,"content":{"t":"encrypted","c":"$encryptedAgentMessage"},"createdAt":1720000000000,"updatedAt":1720000000000},{"id":"message-10","seq":10,"localId":null,"content":{"t":"encrypted","c":"$encryptedSessionMessage"},"createdAt":1720000000001,"updatedAt":1720000000001}],"hasMore":false}"""
            )
        )
        server.enqueue(MockResponse().setBody("{}"))
        val api = HappySyncApi(
            client = OkHttpClient(),
            json = json,
            serverUrl = server.url("/").toString(),
            clientId = "android/test",
        )
        val credentials = HappyCredentials("happy-token", HappySecretKeyCodec.encodeBase64Url(ByteArray(32)))
        val session = HappySession(
            id = "session-1",
            name = "test",
            path = null,
            host = null,
            machineId = null,
            codexThreadId = null,
            active = true,
            activeAt = 1720000000000,
            approvals = emptyList(),
            encryptionKey = key,
            encryptionVariant = HappyEncryptionVariant.DATA_KEY,
        )

        val messages = api.fetchMessages(credentials, session, afterSeq = 8)
        api.sendMessage(credentials, session, "继续")
        val historyRequest = server.takeRequest()
        val sendRequest = server.takeRequest()
        val sentBody = json.parseToJsonElement(sendRequest.body.readUtf8()).jsonObject
        val sentEncrypted = sentBody["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content
        val sentRecord = crypto.decryptJson(sentEncrypted, key, HappyEncryptionVariant.DATA_KEY)

        assertEquals("/v3/sessions/session-1/messages?after_seq=8&limit=500", historyRequest.path)
        assertEquals("闭环完成", messages.first().text)
        assertEquals(9, messages.first().seq)
        assertEquals("运行测试", messages.last().text)
        assertEquals("tool-call-start", messages.last().kind)
        assertEquals("/v3/sessions/session-1/messages", sendRequest.path)
        assertEquals("user", sentRecord?.get("role")?.jsonPrimitive?.content)
        assertEquals("继续", sentRecord?.get("content")?.jsonObject?.get("text")?.jsonPrimitive?.content)
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
