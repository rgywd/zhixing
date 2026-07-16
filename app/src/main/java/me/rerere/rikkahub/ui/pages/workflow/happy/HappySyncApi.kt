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
import okhttp3.OkHttpClient
import okhttp3.Request

class HappySyncApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val recordCrypto: HappyRecordCrypto = HappyRecordCrypto(json),
    private val serverUrl: String = HappyProtocol.SERVER_URL,
    private val clientId: String,
) {
    suspend fun fetchSnapshot(credentials: HappyCredentials): HappySnapshot = withContext(Dispatchers.IO) {
        val accountSecret = HappySecretKeyCodec.decode(credentials.secret)
        val machines = json.decodeFromString<List<RawMachine>>(
            get("/v1/machines", credentials.token)
        ).map { machine -> machine.toMachine(accountSecret) }
        val sessions = json.decodeFromString<RawSessionsResponse>(
            get("/v2/sessions/active?limit=150", credentials.token)
        ).sessions.map { session -> session.toSession(accountSecret) }

        HappySnapshot(machines = machines, sessions = sessions)
    }

    private fun get(path: String, token: String): String {
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}$path")
            .header("Authorization", "Bearer $token")
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

    private fun RawMachine.toMachine(accountSecret: ByteArray): HappyMachine {
        val metadata = decryptRecord(metadata, dataEncryptionKey, accountSecret)
        val cliAvailability = metadata?.get("cliAvailability") as? JsonObject
        return HappyMachine(
            id = id,
            host = metadata.string("host") ?: "未知机器",
            displayName = metadata.string("displayName"),
            platform = metadata.string("platform"),
            active = active,
            activeAt = activeAt,
            supportsCodex = cliAvailability?.get("codex")?.jsonPrimitive?.booleanOrNull,
        )
    }

    private fun RawSession.toSession(accountSecret: ByteArray): HappySession {
        val metadata = decryptRecord(metadata, dataEncryptionKey, accountSecret)
        val agentState = agentState?.let { decryptRecord(it, dataEncryptionKey, accountSecret) }
        val requests = agentState?.get("requests") as? JsonObject
        return HappySession(
            id = id,
            name = metadata.string("name") ?: metadata.summaryText(),
            path = metadata.string("path"),
            host = metadata.string("host"),
            machineId = metadata.string("machineId"),
            codexThreadId = metadata.string("codexThreadId"),
            active = active,
            activeAt = activeAt,
            pendingApprovals = requests?.size ?: 0,
        )
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
)

data class HappySession(
    val id: String,
    val name: String?,
    val path: String?,
    val host: String?,
    val machineId: String?,
    val codexThreadId: String?,
    val active: Boolean,
    val activeAt: Long,
    val pendingApprovals: Int,
)

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
