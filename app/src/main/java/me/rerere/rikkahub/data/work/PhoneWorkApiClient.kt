package me.rerere.rikkahub.data.work

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.life.InformationMonitorDigestEnvelope
import me.rerere.rikkahub.data.life.InformationMonitorFreshness
import me.rerere.rikkahub.data.life.InformationMonitorItemsEnvelope
import me.rerere.rikkahub.data.life.InformationMonitorQuery
import me.rerere.rikkahub.data.life.InformationMonitorSnapshot
import me.rerere.rikkahub.data.life.InformationMonitorStatusEnvelope
import me.rerere.rikkahub.data.life.requireValid
import me.rerere.rikkahub.data.quota.QuotaEnvelope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLConnection
import java.util.UUID
import java.util.concurrent.TimeUnit

class PhoneWorkApiClient(
    private val credentialStore: PhoneWorkCredentialStore,
    private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(200, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun runners(): List<PhoneWorkRunner> = get<RunnersResponse>("/v1/work/runners").runners

    suspend fun repos(runnerId: String): List<PhoneWorkRepo> =
        get<ReposResponse>("/v1/work/repos?runnerId=${runnerId.urlEncode()}").repos

    suspend fun sessions(archived: Boolean = false): List<PhoneWorkSession> =
        get<SessionsResponse>("/v1/work/sessions${if (archived) "?archived=true" else ""}").sessions

    suspend fun quotas(): QuotaEnvelope = get("/v1/life/quotas")

    suspend fun informationMonitorStatus(
        query: InformationMonitorQuery = InformationMonitorQuery(),
    ): InformationMonitorSnapshot<InformationMonitorStatusEnvelope> =
        getInformationMonitor("/v1/life/inbox/status${query.toQueryString()}") {
            it.requireValid()
        }

    suspend fun informationMonitorItems(
        query: InformationMonitorQuery = InformationMonitorQuery(),
    ): InformationMonitorSnapshot<InformationMonitorItemsEnvelope> =
        getInformationMonitor("/v1/life/inbox/items${query.toQueryString()}") {
            it.requireValid()
        }

    suspend fun informationMonitorDigest(
        query: InformationMonitorQuery = InformationMonitorQuery(),
    ): InformationMonitorSnapshot<InformationMonitorDigestEnvelope> =
        getInformationMonitor("/v1/life/inbox/digest${query.toQueryString()}") {
            it.requireValid()
        }

    suspend fun events(sessionId: String, afterSeq: Long): List<PhoneWorkEvent> =
        get<EventsResponse>("/v1/work/sessions/${sessionId.urlEncode()}/events?afterSeq=$afterSeq").events

    fun eventStream(sessionId: String, afterSeq: Long): Flow<PhoneWorkStreamUpdate> = channelFlow {
        withContext(Dispatchers.IO) {
            val token = credentialStore.token() ?: throw PhoneWorkApiException("请先在设置中连接 Work Core")
            val request = Request.Builder()
                .url(url("/v1/work/sessions/${sessionId.urlEncode()}/stream?afterSeq=$afterSeq"))
                .header("Authorization", "Bearer $token")
                .header("X-Zhixing-Work-Protocol", "1")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw response.toApiException()
                trySend(PhoneWorkStreamUpdate.Connected).getOrThrow()
                val source = response.body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        trySend(
                            PhoneWorkStreamUpdate.Event(
                                json.decodeFromString<PhoneWorkEvent>(line.removePrefix("data: ")),
                            )
                        ).getOrThrow()
                    }
                }
                throw IOException("Work 实时连接已关闭")
            }
        }
    }

    suspend fun createSession(request: CreateSessionRequest): PhoneWorkSession =
        post<PhoneWorkSession, CreateSessionRequest>(
            "/v1/work/sessions",
            request,
            idempotencyKey = request.clientMessageId,
        )

    suspend fun sendMessage(sessionId: String, text: String, attachmentIds: List<String>): PhoneWorkEvent {
        val body = SendMessageRequest(
            text = text,
            attachmentIds = attachmentIds,
            clientMessageId = UUID.randomUUID().toString(),
        )
        return post<PhoneWorkEvent, SendMessageRequest>(
            path = "/v1/work/sessions/${sessionId.urlEncode()}/messages",
            body = body,
            idempotencyKey = body.clientMessageId,
        )
    }

    suspend fun uploadImage(uriString: String): PhoneWorkAttachment = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val fileName = queryFileName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "image"
        val mimeType = context.contentResolver.getType(uri)
            ?: URLConnection.guessContentTypeFromName(fileName)
            ?: throw PhoneWorkApiException("无法识别图片格式")
        if (mimeType !in SUPPORTED_IMAGE_TYPES) {
            throw PhoneWorkApiException("Work 仅支持 PNG、JPEG、WebP 和 GIF 图片")
        }
        val data = context.contentResolver.openInputStream(uri)?.use(::readImageBytes)
            ?: throw PhoneWorkApiException("无法读取图片")
        val builder = Request.Builder()
            .url(url("/v1/work/attachments"))
            .header("X-File-Name", Uri.encode(fileName))
            .post(data.toRequestBody(mimeType.toMediaType()))
        request(builder) { response -> json.decodeFromString(response.body?.string().orEmpty()) }
    }

    suspend fun answer(sessionId: String, askId: String, answers: List<PhoneWorkAnswer>) {
        post<UnitResponse, AnswerRequest>(
            path = "/v1/work/sessions/${sessionId.urlEncode()}/asks/${askId.urlEncode()}/answer",
            body = AnswerRequest(answers),
            idempotencyKey = UUID.randomUUID().toString(),
        )
    }

    suspend fun stop(sessionId: String) {
        post<UnitResponse, EmptyRequest>("/v1/work/sessions/${sessionId.urlEncode()}/stop", EmptyRequest)
    }

    suspend fun complete(sessionId: String) {
        post<UnitResponse, EmptyRequest>("/v1/work/sessions/${sessionId.urlEncode()}/complete", EmptyRequest)
    }

    suspend fun archive(sessionId: String): PhoneWorkSession =
        post("/v1/work/sessions/${sessionId.urlEncode()}/archive", EmptyRequest)

    suspend fun unarchive(sessionId: String): PhoneWorkSession =
        post("/v1/work/sessions/${sessionId.urlEncode()}/unarchive", EmptyRequest)

    suspend fun reportHtml(reportId: String): String = request(
        Request.Builder().url(url("/v1/work/reports/${reportId.urlEncode()}")),
    ) { it.body?.string().orEmpty() }

    private suspend inline fun <reified T> get(path: String): T =
        request(Request.Builder().url(url(path))) { response ->
            json.decodeFromString(response.body?.string().orEmpty())
        }

    private suspend inline fun <reified T> getInformationMonitor(
        path: String,
        crossinline validate: (T) -> T,
    ): InformationMonitorSnapshot<T> = request(Request.Builder().url(url(path))) { response ->
        InformationMonitorSnapshot(
            data = validate(json.decodeFromString(response.body?.string().orEmpty())),
            freshness = parseInformationMonitorFreshness(
                response.header(INFORMATION_MONITOR_CACHE_HEADER),
            ),
        )
    }

    private suspend inline fun <reified T, reified B> post(
        path: String,
        body: B,
        idempotencyKey: String? = null,
    ): T {
        val requestBody = json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(url(path)).post(requestBody)
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        return request(builder) { response -> json.decodeFromString(response.body?.string().orEmpty()) }
    }

    private suspend fun <T> request(builder: Request.Builder, transform: (okhttp3.Response) -> T): T =
        withContext(Dispatchers.IO) {
            val token = credentialStore.token() ?: throw PhoneWorkApiException("请先在设置中连接 Work Core")
            val request = builder
                .header("Authorization", "Bearer $token")
                .header("X-Zhixing-Work-Protocol", "1")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw response.toApiException()
                transform(response)
            }
        }

    private fun okhttp3.Response.toApiException(): PhoneWorkApiException {
        val detail = body.string().take(300).trim()
        return PhoneWorkApiException(
            buildString {
                append("Work Core 返回 $code")
                if (detail.isNotBlank()) append(": $detail")
            }
        )
    }

    private fun url(path: String): String {
        val baseUrl = credentialStore.connection.value.baseUrl
        if (baseUrl.isBlank()) throw PhoneWorkApiException("请先在设置中连接 Work Core")
        return "$baseUrl$path"
    }

    private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun InformationMonitorQuery.toQueryString(): String {
        require(hours == null || hours in 1..168) { "hours must be between 1 and 168" }
        require(limit == null || limit in 1..50) { "limit must be between 1 and 50" }
        val parameters = buildList {
            channel?.let { add("channel" to it.name.lowercase()) }
            hours?.let { add("hours" to it.toString()) }
            limit?.let { add("limit" to it.toString()) }
            minImportance?.let { add("minImportance" to it.name.lowercase()) }
        }
        return parameters
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "?", separator = "&") { (name, value) ->
                "${name.urlEncode()}=${value.urlEncode()}"
            }
            .orEmpty()
    }

    private fun queryFileName(uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun readImageBytes(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_IMAGE_BYTES) throw PhoneWorkApiException("单张图片不能超过 10 MiB")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val SUPPORTED_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        const val INFORMATION_MONITOR_CACHE_HEADER = "X-Zhixing-Life-Cache"
    }
}

internal fun parseInformationMonitorFreshness(value: String?): InformationMonitorFreshness =
    when (value?.trim()?.lowercase()) {
        null, "", InformationMonitorFreshness.FRESH.value -> InformationMonitorFreshness.FRESH
        InformationMonitorFreshness.STALE.value -> InformationMonitorFreshness.STALE
        else -> throw PhoneWorkApiException("Work Core 返回了无效的信息监控新鲜度")
    }

class PhoneWorkApiException(message: String, cause: Throwable? = null) : IOException(message, cause)

sealed interface PhoneWorkStreamUpdate {
    data object Connected : PhoneWorkStreamUpdate
    data class Event(val event: PhoneWorkEvent) : PhoneWorkStreamUpdate
}

@Serializable
data class CreateSessionRequest(
    val runnerId: String,
    val repoId: String,
    val title: String,
    val runtime: String = "codex",
    val model: String,
    val reasoningEffort: String,
    val message: String,
    val attachmentIds: List<String> = emptyList(),
    val clientMessageId: String = UUID.randomUUID().toString(),
)

@Serializable private data class SendMessageRequest(
    val text: String,
    val attachmentIds: List<String>,
    val clientMessageId: String,
)
@Serializable private data class AnswerRequest(val answers: List<PhoneWorkAnswer>)
@Serializable private data object EmptyRequest
@Serializable private class UnitResponse
@Serializable private data class RunnersResponse(val runners: List<PhoneWorkRunner>)
@Serializable private data class ReposResponse(val repos: List<PhoneWorkRepo>)
@Serializable private data class SessionsResponse(val sessions: List<PhoneWorkSession>)
@Serializable private data class EventsResponse(val events: List<PhoneWorkEvent>)
