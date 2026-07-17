package me.rerere.rikkahub.ui.pages.workflow.happy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class HappySyncApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val recordCrypto: HappyRecordCrypto = HappyRecordCrypto(json),
    private val serverUrl: String? = null,
    private val clientId: String,
) {
    suspend fun fetchSnapshot(credentials: HappyCredentials): HappySnapshot = withContext(Dispatchers.IO) {
        val accountSecret = HappySecretKeyCodec.decode(credentials.secret)
        val machines = json.decodeFromString<List<RawMachine>>(
            get("/v1/machines", credentials)
        ).map { machine -> machine.toMachine(accountSecret) }
        val sessions = fetchAllSessions(credentials)
            .map { session -> session.toSession(accountSecret) }
            .sortedByDescending(HappySession::updatedAt)

        HappySnapshot(machines = machines, sessions = sessions)
    }

    private fun fetchAllSessions(credentials: HappyCredentials): List<RawSession> {
        val sessions = mutableListOf<RawSession>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val query = buildString {
                append("/v2/sessions?limit=200")
                cursor?.let { append("&cursor=").append(it) }
            }
            val response = json.decodeFromString<RawSessionsResponse>(get(query, credentials))
            sessions += response.sessions
            cursor = response.nextCursor?.takeIf { response.hasNext && seenCursors.add(it) }
        } while (cursor != null)
        return sessions.distinctBy(RawSession::id)
    }

    suspend fun fetchMessages(
        credentials: HappyCredentials,
        session: HappySession,
        afterSeq: Long = 0,
    ): List<HappyRecord> = withContext(Dispatchers.IO) {
        val key = session.encryptionKey ?: throw HappyDecryptionException(session.id)
        val result = mutableListOf<HappyRecord>()
        var cursor = afterSeq
        var hasMore: Boolean
        var madeProgress: Boolean
        do {
            val response = json.decodeFromString<RawMessagesResponse>(
                get("/v3/sessions/${session.id}/messages?after_seq=$cursor&limit=500", credentials)
            )
            response.messages.mapNotNullTo(result) { message ->
                if (message.content.t != "encrypted") return@mapNotNullTo null
                val raw = recordCrypto.decryptJson(message.content.c, key, session.encryptionVariant)
                    ?: return@mapNotNullTo null
                HappyRecord(
                    id = message.id,
                    seq = message.seq,
                    createdAt = message.createdAt,
                    body = raw,
                )
            }
            val nextCursor = response.messages.maxOfOrNull(RawMessage::seq) ?: cursor
            madeProgress = nextCursor > cursor
            cursor = nextCursor
            hasMore = response.hasMore
        } while (hasMore && madeProgress)
        result
    }

    suspend fun sendMessage(
        credentials: HappyCredentials,
        session: HappySession,
        text: String,
        permissionMode: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
        disallowedTools: List<String>? = null,
    ) = withContext(Dispatchers.IO) {
        val key = session.encryptionKey ?: throw HappyDecryptionException(session.id)
        val localId = UUID.randomUUID().toString()
        val record = buildJsonObject {
            put("role", "user")
            put("content", buildJsonObject {
                put("type", "text")
                put("text", text)
            })
            put("meta", buildJsonObject {
                put("sentFrom", "android")
                permissionMode?.let { put("permissionMode", it) }
                model?.let { put("model", it) }
                reasoningEffort?.let { put("reasoningEffort", it) }
                disallowedTools?.takeIf { it.isNotEmpty() }?.let { tools ->
                    putJsonArray("disallowedTools") { tools.forEach(::add) }
                }
            })
        }
        val encrypted = recordCrypto.encryptElement(record, key, session.encryptionVariant)
        val body = buildJsonObject {
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("localId", localId)
                    put("content", encrypted)
                })
            }
        }.toString()
        post("/v3/sessions/${session.id}/messages", credentials, body)
    }

    private fun get(path: String, credentials: HappyCredentials): String {
        val request = Request.Builder()
            .url("${(serverUrl ?: credentials.serverUrl).trimEnd('/')}$path")
            .header("Authorization", "Bearer ${credentials.token}")
            .header("Content-Type", "application/json")
            .header("X-Happy-Client", clientId)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw HappySyncException(response.code, path)
            }
            body
        }
    }

    private fun post(path: String, credentials: HappyCredentials, body: String): String {
        val request = Request.Builder()
            .url("${(serverUrl ?: credentials.serverUrl).trimEnd('/')}$path")
            .header("Authorization", "Bearer ${credentials.token}")
            .header("Content-Type", "application/json")
            .header("X-Happy-Client", clientId)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) throw HappySyncException(response.code, path)
            responseBody
        }
    }

    private fun RawMachine.toMachine(accountSecret: ByteArray): HappyMachine {
        val resolved = resolveRecordKey(dataEncryptionKey, accountSecret)
        val metadata = resolved?.let { recordCrypto.decryptJson(metadata, it.first, it.second) }
        val cliAvailability = metadata?.get("cliAvailability") as? JsonObject
        return HappyMachine(
            id = id,
            host = metadata.string("host") ?: "未知机器",
            displayName = metadata.string("displayName"),
            platform = metadata.string("platform"),
            active = active,
            activeAt = activeAt,
            supportsCodex = cliAvailability?.get("codex")?.jsonPrimitive?.booleanOrNull,
            supportsClaude = cliAvailability?.get("claude")?.jsonPrimitive?.booleanOrNull,
            homeDir = metadata.string("homeDir"),
            encryptionKey = resolved?.first,
            encryptionVariant = resolved?.second ?: HappyEncryptionVariant.DATA_KEY,
        )
    }

    private fun RawSession.toSession(accountSecret: ByteArray): HappySession {
        val resolved = resolveRecordKey(dataEncryptionKey, accountSecret)
        val metadata = resolved?.let { recordCrypto.decryptJson(metadata, it.first, it.second) }
        val agentState = agentState?.let { encrypted ->
            resolved?.let { recordCrypto.decryptJson(encrypted, it.first, it.second) }
        }
        val requests = agentState?.get("requests") as? JsonObject
        return HappySession(
            id = id,
            name = metadata.string("name") ?: metadata.summaryText(),
            path = metadata.string("path"),
            host = metadata.string("host"),
            machineId = metadata.string("machineId"),
            codexThreadId = metadata.string("codexThreadId"),
            flavor = metadata.string("flavor"),
            active = active,
            activeAt = activeAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            approvals = requests?.map { (id, value) ->
                val request = value as? JsonObject
                HappyApproval(
                    id = id,
                    tool = request.string("tool") ?: "未知工具",
                    arguments = request?.get("arguments")?.toString().orEmpty(),
                )
            }.orEmpty(),
            encryptionKey = resolved?.first,
            encryptionVariant = resolved?.second ?: HappyEncryptionVariant.DATA_KEY,
        )
    }

    private fun resolveRecordKey(
        wrappedDataKey: String?,
        accountSecret: ByteArray,
    ): Pair<ByteArray, HappyEncryptionVariant>? = if (wrappedDataKey == null) {
        accountSecret to HappyEncryptionVariant.LEGACY
    } else {
        recordCrypto.unwrapDataKey(wrappedDataKey, accountSecret)
            ?.let { it to HappyEncryptionVariant.DATA_KEY }
    }

    private fun decryptRecord(
        encrypted: String,
        wrappedDataKey: String?,
        accountSecret: ByteArray,
    ): JsonObject? {
        if (wrappedDataKey == null) {
            return recordCrypto.decryptJson(
                encoded = encrypted,
                key = accountSecret,
                variant = HappyEncryptionVariant.LEGACY,
            )
        }
        val dataKey = recordCrypto.unwrapDataKey(wrappedDataKey, accountSecret) ?: return null
        return recordCrypto.decryptJson(encrypted, dataKey)
    }

    private fun JsonObject?.string(key: String): String? =
        this?.get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject?.summaryText(): String? =
        (this?.get("summary") as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

data class HappySnapshot(
    val machines: List<HappyMachine>,
    val sessions: List<HappySession>,
)

data class HappyMachine(
    val id: String,
    val host: String,
    val displayName: String?,
    val platform: String?,
    val active: Boolean,
    val activeAt: Long,
    val supportsCodex: Boolean?,
    val supportsClaude: Boolean?,
    val homeDir: String?,
    val encryptionKey: ByteArray?,
    val encryptionVariant: HappyEncryptionVariant,
)

data class HappySession(
    val id: String,
    val name: String?,
    val path: String?,
    val host: String?,
    val machineId: String?,
    val codexThreadId: String?,
    val flavor: String?,
    val active: Boolean,
    val activeAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val approvals: List<HappyApproval>,
    val encryptionKey: ByteArray?,
    val encryptionVariant: HappyEncryptionVariant,
)

data class HappyApproval(val id: String, val tool: String, val arguments: String)

/** 解密后的原始消息记录，结构化解析交给 WorkMessageParser */
data class HappyRecord(
    val id: String,
    val seq: Long,
    val createdAt: Long,
    val body: JsonObject,
)

class HappyDecryptionException(val recordId: String) : Exception("Unable to decrypt Happy record")

class HappySyncException(
    val statusCode: Int,
    val endpoint: String,
) : Exception("Happy sync failed with status $statusCode")

@Serializable
private data class RawMachine(
    val id: String,
    val metadata: String,
    val metadataVersion: Long,
    val daemonState: String? = null,
    val daemonStateVersion: Long = 0,
    val dataEncryptionKey: String? = null,
    val seq: Long,
    val active: Boolean,
    val activeAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
private data class RawSessionsResponse(
    val sessions: List<RawSession>,
    val nextCursor: String? = null,
    val hasNext: Boolean = false,
)

@Serializable
private data class RawSession(
    val id: String,
    val seq: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val active: Boolean,
    val activeAt: Long,
    val metadata: String,
    val metadataVersion: Long,
    val agentState: String? = null,
    val agentStateVersion: Long,
    val dataEncryptionKey: String? = null,
)

@Serializable
private data class RawMessagesResponse(val messages: List<RawMessage>, val hasMore: Boolean = false)

@Serializable
private data class RawMessage(
    val id: String,
    val seq: Long,
    val localId: String? = null,
    val content: RawMessageContent,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
private data class RawMessageContent(val c: String, val t: String)
