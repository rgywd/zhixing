package me.rerere.rikkahub.data.workflow.wire

import com.iwebpp.crypto.TweetNaclFast
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.workflow.codex.CatalogSnapshotPayload
import me.rerere.rikkahub.data.workflow.codex.CatalogSnapshotChunkPayload
import me.rerere.rikkahub.data.workflow.codex.WireCatalogSink
import me.rerere.rikkahub.data.workflow.codex.ThreadDetailPayload
import me.rerere.rikkahub.data.workflow.codex.RuntimeCommandPayload
import me.rerere.rikkahub.data.workflow.codex.RuntimeEventPayload
import me.rerere.rikkahub.data.workflow.codex.CommandResultPayload
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class WireRelayClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val credentialsStore: WireCredentialsStore,
    private val catalogRepository: WireCatalogSink,
    private val crypto: WireCrypto = WireCrypto(),
) {
    private val secureRandom = SecureRandom()
    private val operationMutex = Mutex()
    private val commandResults = ArrayDeque<CommandResultPayload>()
    val connected: Boolean get() = credentialsStore.load() != null

    suspend fun connect(recoveryKey: String, serverUrl: String): WireRelayCredentials {
        val origin = normalizeServerUrl(serverUrl)
        val rootSecret = WireRecoveryKeyCodec.decode(recoveryKey)
        val authKeyPair = TweetNaclFast.Signature.keyPair_fromSeed(WireKeys.deriveAuthSeed(rootSecret))
        val publicKey = authKeyPair.publicKey.toBase64Url()
        val challenge = post<ChallengeResponse>(
            origin,
            "/v1/auth/challenges",
            json.encodeToString(ChallengeRequest(publicKey)),
        )
        val signatureMessage = authSignatureMessage(challenge)
        val signature = requireNotNull(
            TweetNaclFast.Signature(authKeyPair.publicKey, authKeyPair.secretKey).detached(signatureMessage)
        )
        val verified = post<AuthResponse>(
            origin,
            "/v1/auth/verify",
            json.encodeToString(VerifyRequest(challenge.challengeId, publicKey, signature.toBase64Url())),
        )
        val deviceId = credentialsStore.load()?.deviceId ?: UUID.randomUUID().toString()
        val credentials = WireRelayCredentials(
            serverUrl = origin,
            accountId = verified.accountId,
            deviceId = deviceId,
            token = verified.token,
            tokenExpiresAt = verified.expiresAt,
            rootSecret = WireRecoveryKeyCodec.encode(rootSecret),
        )
        post<JsonObject>(
            origin,
            "/v1/devices",
            json.encodeToString(
                DeviceRequest(deviceId, WireKeys.deriveContentPublicKey(rootSecret).toBase64Url(), "android")
            ),
            credentials,
        )
        credentialsStore.save(credentials)
        return credentials
    }

    suspend fun sync(limit: Int = 100): WireSyncResult = operationMutex.withLock {
        var credentials = requireNotNull(credentialsStore.load()) { "开发环境尚未连接" }
        val response = get<OutboxResponse>(credentials, "/v1/outbox?limit=${limit.coerceIn(1, 200)}")
        var applied = 0
        for (raw in response.envelopes) {
            val acknowledgementKey = "${raw.senderDeviceId}|${raw.streamId}"
            val previous = credentials.acknowledgements[acknowledgementKey]
            if (previous != null && raw.seq > previous + 1) {
                return WireSyncResult(applied, true)
            }
            if (previous != null && raw.seq <= previous) {
                acknowledge(credentials, raw)
                continue
            }
            credentials = if (raw.streamId.startsWith(KEY_STREAM_PREFIX)) {
                applyWrappedKey(credentials, raw)
            } else {
                applyPayload(credentials, raw)
                credentials
            }
            credentials = credentials.copy(
                acknowledgements = credentials.acknowledgements + (acknowledgementKey to raw.seq)
            )
            credentialsStore.save(credentials)
            acknowledge(credentials, raw)
            applied += 1
        }
        WireSyncResult(applied, false)
    }

    suspend fun sendRuntimeCommand(command: RuntimeCommandPayload): String = operationMutex.withLock {
        var credentials = requireNotNull(credentialsStore.load()) { "开发环境尚未连接" }
        credentials = flushPending(credentials)
        val devices = get<DevicesResponse>(credentials, "/v1/devices").devices
        val recipient = devices.firstOrNull {
            it.deviceId == command.machineId && it.deviceType == "agent" && it.revokedAt == null
        } ?: error("目标开发机当前未连接")
        val requestId = UUID.randomUUID().toString().replace('-', '_')
        val dataKey = ByteArray(32).also(secureRandom::nextBytes)
        val keyId = UUID.randomUUID().toString().replace('-', '_')
        val keyStream = "keys_${recipient.deviceId}"
        val keyHeader = nextHeader(credentials, keyStream, recipient.deviceId, keyId)
        credentials = submit(
            credentials,
            RelayEnvelope.from(
                keyHeader,
                WireKeys.wrapDataKey(dataKey, Base64.getUrlDecoder().decode(recipient.publicKey), secureRandom),
            ),
        )
        val streamId = "commands_${credentials.deviceId}_${recipient.deviceId}"
        val header = nextHeader(credentials, streamId, recipient.deviceId, keyId)
        val payload = WirePayload(
            type = "runtime.command",
            schema = 1,
            requestId = requestId,
            sentAt = System.currentTimeMillis(),
            body = json.encodeToJsonElement(command).jsonObject,
        )
        val encrypted = crypto.encryptBytes(json.encodeToString(payload).toByteArray(), dataKey, header)
        submit(credentials, RelayEnvelope.from(header, encrypted))
        requestId
    }

    @Synchronized
    fun consumeCommandResult(
        machineId: String,
        threadId: String?,
        command: String? = null,
        requestId: String? = null,
    ): CommandResultPayload? {
        val result = commandResults.firstOrNull {
            it.machineId == machineId && (threadId == null || it.threadId == threadId) &&
                (command == null || it.command == command) && (requestId == null || it.requestId == requestId)
        } ?: return null
        commandResults.remove(result)
        return result
    }

    fun disconnect() = credentialsStore.clear()

    private fun applyWrappedKey(credentials: WireRelayCredentials, envelope: RelayEnvelope): WireRelayCredentials {
        val secret = WireKeys.deriveContentSecretKey(WireRecoveryKeyCodec.decode(credentials.rootSecret))
        requireNotNull(WireKeys.unwrapDataKey(envelope.cipherBundle, secret)) { "无法解封目录密钥" }
        return credentials.copy(wrappedKeys = credentials.wrappedKeys + (envelope.keyId to envelope.cipherBundle))
    }

    private suspend fun applyPayload(credentials: WireRelayCredentials, raw: RelayEnvelope) {
        val wrappedKey = requireNotNull(credentials.wrappedKeys[raw.keyId]) { "缺少数据密钥 ${raw.keyId}" }
        val secret = WireKeys.deriveContentSecretKey(WireRecoveryKeyCodec.decode(credentials.rootSecret))
        val dataKey = requireNotNull(WireKeys.unwrapDataKey(wrappedKey, secret)) { "数据密钥已损坏" }
        val envelope = raw.toWireEnvelope()
        val payload = json.decodeFromString<WirePayload>(
            crypto.decryptBytes(envelope.cipherBundle, dataKey, envelope.header).toString(Charsets.UTF_8)
        )
        require(payload.schema == 1) { "不支持的 payload schema ${payload.schema}" }
        when (payload.type) {
            "catalog.snapshot" -> catalogRepository.applySnapshot(json.decodeFromJsonElement(payload.body))
            "catalog.snapshot.chunk" -> catalogRepository.applySnapshotChunk(json.decodeFromJsonElement(payload.body))
            "thread.detail" -> catalogRepository.applyThreadDetail(json.decodeFromJsonElement<ThreadDetailPayload>(payload.body))
            "runtime.event" -> catalogRepository.applyRuntimeEvent(json.decodeFromJsonElement<RuntimeEventPayload>(payload.body))
            "command.result" -> {
                val result = json.decodeFromJsonElement<CommandResultPayload>(payload.body)
                    .copy(requestId = payload.requestId)
                synchronized(this) {
                    commandResults.addLast(result)
                    while (commandResults.size > MAX_COMMAND_RESULTS) commandResults.removeFirst()
                }
                catalogRepository.applyCommandResult(result)
            }
            else -> error("不支持的 Wire payload: ${payload.type}")
        }
    }

    private suspend fun flushPending(initial: WireRelayCredentials): WireRelayCredentials {
        var credentials = initial
        for (envelope in initial.pendingEnvelopes.values) {
            credentials = submit(credentials, envelope.toRelayEnvelope())
        }
        return credentials
    }

    private fun nextHeader(
        credentials: WireRelayCredentials,
        streamId: String,
        targetId: String,
        keyId: String,
    ) = WireEnvelopeHeader(
        v = 1,
        id = UUID.randomUUID().toString().replace('-', '_'),
        accountId = credentials.accountId,
        senderDeviceId = credentials.deviceId,
        targetId = targetId,
        streamId = streamId,
        seq = (credentials.outgoingSequences[streamId] ?: 0L) + 1L,
        createdAt = System.currentTimeMillis(),
        expiresAt = null,
        keyId = keyId,
    )

    private suspend fun submit(initial: WireRelayCredentials, envelope: RelayEnvelope): WireRelayCredentials {
        val streamId = envelope.streamId
        val pending = initial.pendingEnvelopes[streamId]?.toRelayEnvelope() ?: envelope
        var credentials = initial
        if (streamId !in credentials.pendingEnvelopes) {
            credentials = credentials.copy(
                pendingEnvelopes = credentials.pendingEnvelopes + (streamId to pending.toStoredEnvelope())
            )
            credentialsStore.save(credentials)
        }
        post<JsonObject>(
            credentials.serverUrl,
            "/v1/envelopes",
            json.encodeToString(pending),
            credentials,
        )
        credentials = credentials.copy(
            outgoingSequences = credentials.outgoingSequences + (streamId to pending.seq),
            pendingEnvelopes = credentials.pendingEnvelopes - streamId,
        )
        credentialsStore.save(credentials)
        return credentials
    }

    private suspend fun acknowledge(credentials: WireRelayCredentials, envelope: RelayEnvelope) {
        post<JsonObject>(
            credentials.serverUrl,
            "/v1/acks",
            json.encodeToString(AckRequest(envelope.senderDeviceId, envelope.streamId, envelope.seq)),
            credentials,
        )
    }

    private suspend inline fun <reified T> get(credentials: WireRelayCredentials, path: String): T = request(
        Request.Builder()
            .url("${credentials.serverUrl}$path")
            .header("Authorization", "Bearer ${credentials.token}")
            .header("X-Zhixing-Device-Id", credentials.deviceId)
            .get()
            .build()
    )

    private suspend inline fun <reified T> post(
        origin: String,
        path: String,
        bodyJson: String,
        credentials: WireRelayCredentials? = null,
    ): T {
        val builder = Request.Builder()
            .url("$origin$path")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
        if (credentials != null) {
            builder.header("Authorization", "Bearer ${credentials.token}")
                .header("X-Zhixing-Device-Id", credentials.deviceId)
        }
        return request(builder.build())
    }

    private suspend inline fun <reified T> request(request: Request): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw WireRelayException(response.code, body.take(300))
            json.decodeFromString(body)
        }
    }

    companion object {
        private const val KEY_STREAM_PREFIX = "keys_"
        private const val MAX_COMMAND_RESULTS = 100
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun normalizeServerUrl(raw: String): String {
            val value = raw.trim().trimEnd('/')
            val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("中继地址格式不正确")
            require(url.encodedPath == "/" && url.query == null && url.fragment == null) {
                "中继地址不能包含路径、查询参数或片段"
            }
            require(url.scheme == "https" || url.host == "localhost" || url.host == "127.0.0.1") {
                "中继地址必须使用 HTTPS"
            }
            return value
        }

        private fun authSignatureMessage(challenge: ChallengeResponse): ByteArray {
            val expiresAt = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(challenge.expiresAt).array()
            return "Zhixing Relay auth v1\n".toByteArray() +
                challenge.challengeId.toByteArray() + "\n".toByteArray() +
                Base64.getUrlDecoder().decode(challenge.challenge) + expiresAt
        }
    }
}

data class WireSyncResult(val appliedEnvelopes: Int, val gapDetected: Boolean)

class WireRelayException(val statusCode: Int, message: String) : Exception(message)

@Serializable private data class ChallengeRequest(val publicKey: String)
@Serializable private data class ChallengeResponse(
    val challengeId: String,
    val challenge: String,
    val expiresAt: Long,
)
@Serializable private data class VerifyRequest(val challengeId: String, val publicKey: String, val signature: String)
@Serializable private data class AuthResponse(val accountId: String, val token: String, val expiresAt: Long)
@Serializable private data class DeviceRequest(val deviceId: String, val publicKey: String, val deviceType: String)
@Serializable private data class OutboxResponse(val envelopes: List<RelayEnvelope>)
@Serializable private data class DevicesResponse(val devices: List<RelayDevice>)
@Serializable private data class RelayDevice(
    val deviceId: String,
    val publicKey: String,
    val deviceType: String,
    val lastSeenAt: Long,
    val revokedAt: Long? = null,
)
@Serializable private data class AckRequest(val senderDeviceId: String, val streamId: String, val seq: Long)

@Serializable
data class RelayEnvelope(
    val v: Int,
    val id: String,
    val accountId: String,
    val senderDeviceId: String,
    val targetId: String,
    val streamId: String,
    val seq: Long,
    val createdAt: Long,
    val expiresAt: Long?,
    val keyId: String,
    val cipherBundle: String,
) {
    fun toWireEnvelope() = WireEnvelope(
        header = WireEnvelopeHeader(v, id, accountId, senderDeviceId, targetId, streamId, seq, createdAt, expiresAt, keyId),
        cipherBundle = cipherBundle,
    )

    fun toStoredEnvelope() = WireStoredEnvelope(
        v, id, accountId, senderDeviceId, targetId, streamId, seq, createdAt, expiresAt, keyId, cipherBundle
    )

    companion object {
        fun from(header: WireEnvelopeHeader, cipherBundle: String) = RelayEnvelope(
            header.v,
            header.id,
            header.accountId,
            header.senderDeviceId,
            header.targetId,
            header.streamId,
            header.seq,
            header.createdAt,
            header.expiresAt,
            header.keyId,
            cipherBundle,
        )
    }
}

@Serializable
private data class WirePayload(
    val type: String,
    val schema: Int,
    val requestId: String? = null,
    val sentAt: Long,
    val body: JsonObject,
)

private fun WireStoredEnvelope.toRelayEnvelope() = RelayEnvelope(
    v, id, accountId, senderDeviceId, targetId, streamId, seq, createdAt, expiresAt, keyId, cipherBundle
)

private fun ByteArray.toBase64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
