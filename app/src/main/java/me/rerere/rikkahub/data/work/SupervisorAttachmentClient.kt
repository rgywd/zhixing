package me.rerere.rikkahub.data.work

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class SupervisorAttachmentReceipt(
    val attachmentId: String,
    val localPath: String,
    val fileName: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val expiresAt: Long,
)

class SupervisorAttachmentClient(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun runtimeFacts(connection: WorkConnectionCredentials): AppServerRuntimeFacts = withContext(Dispatchers.IO) {
        val origin = supervisorOrigin(connection)
        val token = supervisorToken(connection)
        val response = client.newCall(
            Request.Builder()
                .url("$origin/v1/status")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        ).execute()
        response.use {
            check(it.isSuccessful) { "Supervisor 状态读取失败（HTTP ${it.code}）" }
            SupervisorRuntimeFactsMapper.map(json.parseToJsonElement(it.body.string()).jsonObject)
        }
    }

    suspend fun upload(
        connection: WorkConnectionCredentials,
        uri: Uri,
        fileName: String,
        mime: String,
    ): SupervisorAttachmentReceipt = withContext(Dispatchers.IO) {
        val origin = supervisorOrigin(connection)
        val token = supervisorToken(connection)
        val bytes = when (uri.scheme) {
            "file" -> File(requireNotNull(uri.path)).readBytes()
            else -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取附件")
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "附件必须小于 25 MB" }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val encodedName = Base64.encodeToString(
            fileName.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val response = client.newCall(
            Request.Builder()
                .url("$origin/v1/attachments")
                .header("Authorization", "Bearer $token")
                .header("X-Zhixing-File-Name", encodedName)
                .header("X-Content-Sha256", sha256)
                .post(bytes.toRequestBody(mime.toMediaType()))
                .build()
        ).execute()
        response.use {
            check(it.isSuccessful) { "附件上传失败（HTTP ${it.code}）" }
            val receipt = json.decodeFromString<SupervisorAttachmentReceipt>(it.body.string())
            check(receipt.size == bytes.size.toLong() && receipt.sha256.equals(sha256, ignoreCase = true)) {
                "附件回执校验失败"
            }
            receipt
        }
    }

    private companion object {
        const val MAX_BYTES = 25 * 1024 * 1024
    }

    private fun supervisorOrigin(connection: WorkConnectionCredentials): String {
        val origin = requireNotNull(connection.supervisorUrl?.trim()?.trimEnd('/')) {
            "请先在 Work 设置中配置 Supervisor 地址"
        }
        require(origin.startsWith("https://") || origin.startsWith("http://127.0.0.1") || origin.startsWith("http://localhost")) {
            "Supervisor 必须使用 HTTPS"
        }
        return origin
    }

    private fun supervisorToken(connection: WorkConnectionCredentials): String =
        requireNotNull(connection.supervisorToken?.takeIf(String::isNotBlank)) {
            "请先在 Work 设置中配置 Supervisor Token"
        }
}

internal object SupervisorRuntimeFactsMapper {
    fun map(status: JsonObject): AppServerRuntimeFacts {
        val appServer = status["appServer"] as? JsonObject ?: JsonObject(emptyMap())
        val running = (appServer["running"] as? JsonPrimitive)?.booleanOrNull == true
        return AppServerRuntimeFacts(
            codexVersion = (appServer["codexVersion"] as? JsonPrimitive)?.contentOrNull,
            schemaHash = (appServer["schemaHash"] as? JsonPrimitive)?.contentOrNull,
            methods = (appServer["methods"] as? JsonArray)
                .orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .toSet(),
            attachmentSupervisorReady = running,
        )
    }
}
