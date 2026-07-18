package me.rerere.rikkahub.data.workflow.wire

import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.workflow.codex.CatalogMachinePayload
import me.rerere.rikkahub.data.workflow.codex.CatalogSnapshotPayload
import me.rerere.rikkahub.data.workflow.codex.CatalogSnapshotChunkPayload
import me.rerere.rikkahub.data.workflow.codex.ThreadDetailPayload
import me.rerere.rikkahub.data.workflow.codex.WireCatalogSink
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class WireRelayClientTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

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
    fun connectsWithRelayChallengeAndRegistersAndroidDevice() = runBlocking {
        val rootSecret = ByteArray(32) { 4 }
        server.enqueue(jsonResponse("""{"challengeId":"challenge_1","challenge":"${ByteArray(32) { 7 }.base64Url()}","expiresAt":1999999999999}"""))
        server.enqueue(jsonResponse("""{"accountId":"account_1","token":"${"t".repeat(43)}","expiresAt":1999999999999}"""))
        server.enqueue(jsonResponse("""{"device":{"deviceId":"phone_1"}}"""))
        val store = FakeCredentialsStore()

        val credentials = client(store, FakeCatalogSink()).connect(
            WireRecoveryKeyCodec.encode(rootSecret),
            server.url("/").toString(),
        )

        assertEquals("account_1", credentials.accountId)
        assertEquals(3, server.requestCount)
        assertEquals("/v1/auth/challenges", server.takeRequest().path)
        assertEquals("/v1/auth/verify", server.takeRequest().path)
        val registration = server.takeRequest()
        assertEquals("/v1/devices", registration.path)
        assertNotNull(store.load())
    }

    @Test
    fun appliesKeyEnvelopeThenCatalogSnapshotAndAcknowledgesBothStreams() = runBlocking {
        val rootSecret = ByteArray(32) { 9 }
        val dataKey = ByteArray(32) { 6 }
        val credentials = WireRelayCredentials(
            serverUrl = server.url("/").toString().trimEnd('/'),
            accountId = "account_1",
            deviceId = "phone_1",
            token = "t".repeat(43),
            tokenExpiresAt = Long.MAX_VALUE,
            rootSecret = WireRecoveryKeyCodec.encode(rootSecret),
        )
        val keyEnvelope = envelope(
            streamId = "keys_phone_1",
            seq = 5,
            keyId = "key_1",
            cipherBundle = WireKeys.wrapDataKey(dataKey, WireKeys.deriveContentPublicKey(rootSecret)),
        )
        val snapshot = CatalogSnapshotPayload(
            revision = 3,
            generatedAt = 100,
            machine = CatalogMachinePayload(
                machineId = "machine_1",
                displayName = "Minecraft",
                platformFamily = "windows",
                platformOs = "windows",
                agentVersion = "0.2.0",
                runtimeWritable = true,
                lastSeenAt = 100,
            ),
            projects = emptyList(),
            threads = emptyList(),
        )
        val catalogHeader = header(streamId = "catalog_machine_1_phone_1", seq = 11, keyId = "key_1")
        val payload = buildJsonObject {
            put("type", "catalog.snapshot")
            put("schema", 1)
            put("sentAt", 100)
            put("body", json.encodeToJsonElement(snapshot))
        }
        val catalogEnvelope = envelope(
            header = catalogHeader,
            cipherBundle = WireCrypto().encryptBytes(payload.toString().toByteArray(), dataKey, catalogHeader),
        )
        server.enqueue(jsonResponse("""{"envelopes":[$keyEnvelope,$catalogEnvelope]}"""))
        server.enqueue(jsonResponse("""{"seq":5}"""))
        server.enqueue(jsonResponse("""{"seq":11}"""))
        val store = FakeCredentialsStore(credentials)
        val sink = FakeCatalogSink()

        val result = client(store, sink).sync()

        assertEquals(2, result.appliedEnvelopes)
        assertFalse(result.gapDetected)
        assertEquals(3L, sink.snapshot?.revision)
        assertEquals(3, server.requestCount)
        server.takeRequest()
        assertEquals("/v1/acks", server.takeRequest().path)
        assertEquals("/v1/acks", server.takeRequest().path)
    }

    private fun client(store: WireCredentialsStore, sink: WireCatalogSink) = WireRelayClient(
        client = OkHttpClient(),
        json = json,
        credentialsStore = store,
        catalogRepository = sink,
    )

    private fun envelope(
        streamId: String,
        seq: Long,
        keyId: String,
        cipherBundle: String,
    ): String = envelope(header(streamId, seq, keyId), cipherBundle)

    private fun envelope(header: WireEnvelopeHeader, cipherBundle: String): String = buildJsonObject {
        put("v", header.v)
        put("id", header.id)
        put("accountId", header.accountId)
        put("senderDeviceId", header.senderDeviceId)
        put("targetId", header.targetId)
        put("streamId", header.streamId)
        put("seq", header.seq)
        put("createdAt", header.createdAt)
        put("expiresAt", null)
        put("keyId", header.keyId)
        put("cipherBundle", cipherBundle)
    }.toString()

    private fun header(streamId: String, seq: Long, keyId: String) = WireEnvelopeHeader(
        v = 1,
        id = "envelope_${streamId}_$seq",
        accountId = "account_1",
        senderDeviceId = "machine_1",
        targetId = "phone_1",
        streamId = streamId,
        seq = seq,
        createdAt = 100,
        expiresAt = null,
        keyId = keyId,
    )

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}

private class FakeCredentialsStore(initial: WireRelayCredentials? = null) : WireCredentialsStore {
    private var value = initial
    override fun save(credentials: WireRelayCredentials) { value = credentials }
    override fun load(): WireRelayCredentials? = value
    override fun clear() { value = null }
}

private class FakeCatalogSink : WireCatalogSink {
    var snapshot: CatalogSnapshotPayload? = null
    override suspend fun applySnapshot(snapshot: CatalogSnapshotPayload, syncedAt: Long): Boolean {
        this.snapshot = snapshot
        return true
    }
    override suspend fun applySnapshotChunk(chunk: CatalogSnapshotChunkPayload, receivedAt: Long): Boolean = false
    override suspend fun applyThreadDetail(detail: ThreadDetailPayload) = Unit
}

private fun ByteArray.base64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
