package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

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
        val requestBody = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
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
                url = request.url.toString(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

}

internal fun okhttp3.Headers.toRedactedMap(): Map<String, String> = names().associateWith { name ->
    if (SENSITIVE_HTTP_HEADER_NAMES.any(name::equalsIgnoreCase)) "[已隐藏]" else get(name).orEmpty()
}

private fun String.equalsIgnoreCase(other: String): Boolean = equals(other, ignoreCase = true)
