package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response

internal val SENSITIVE_HTTP_HEADER_NAMES = setOf(
    "Authorization",
    "Proxy-Authorization",
    "Cookie",
    "Set-Cookie",
    "Api-Key",
    "X-Api-Key",
    "X-Goog-Api-Key",
    "X-Amz-Security-Token",
)

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toRedactedMap()
        val safeUrl = request.url.run {
            val explicitPort = port.takeIf { it != defaultPort(scheme) }?.let { ":$it" }.orEmpty()
            "$scheme://$host$explicitPort"
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e::class.simpleName ?: "RequestFailed"
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = safeUrl,
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = null,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toRedactedMap()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = safeUrl,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = null,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun defaultPort(scheme: String): Int = when (scheme.lowercase()) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
}

internal fun okhttp3.Headers.toRedactedMap(): Map<String, String> = names().associateWith { name ->
    if (name.isSensitiveHeader()) "[已隐藏]" else get(name).orEmpty()
}

private fun String.isSensitiveHeader(): Boolean {
    val normalized = lowercase()
    return SENSITIVE_HTTP_HEADER_NAMES.any { it.equals(normalized, ignoreCase = true) } ||
        "token" in normalized ||
        "secret" in normalized ||
        "api-key" in normalized ||
        normalized.endsWith("-key")
}
