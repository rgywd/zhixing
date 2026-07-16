package me.rerere.rikkahub.ui.pages.workflow.happy

import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class HappyAuthApi(
    private val client: OkHttpClient,
    private val json: Json,
    private val crypto: HappyCrypto = HappyCrypto(),
    private val serverUrl: String = HappyProtocol.SERVER_URL,
    private val clientId: String,
) {
    suspend fun exchangeRecoveryKey(recoveryKey: String): HappyCredentials {
        val secret = HappySecretKeyCodec.decode(recoveryKey)
        val auth = crypto.createAuthChallenge(secret)
        val body = json.encodeToString(
            AuthRequest(
                challenge = auth.challenge.toBase64(),
                signature = auth.signature.toBase64(),
                publicKey = auth.publicKey.toBase64(),
            )
        )
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/v1/auth")
            .header("X-Happy-Client", clientId)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    throw HappyAuthException(response.code, "Happy authentication failed")
                }
                val token = json.decodeFromString<AuthResponse>(responseBody).token
                HappyCredentials(
                    token = token,
                    secret = HappySecretKeyCodec.encodeBase64Url(secret),
                )
            }
        }
    }

    @Serializable
    private data class AuthRequest(
        val challenge: String,
        val signature: String,
        val publicKey: String,
    )

    @Serializable
    private data class AuthResponse(val token: String)

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

data class HappyCredentials(
    val token: String,
    val secret: String,
)

class HappyAuthException(
    val statusCode: Int,
    message: String,
) : Exception(message)
