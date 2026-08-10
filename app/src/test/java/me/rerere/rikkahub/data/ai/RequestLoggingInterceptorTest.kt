package me.rerere.rikkahub.data.ai

import me.rerere.common.android.Logging
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RequestLoggingInterceptorTest {
    @Before
    fun setUp() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)
    }

    @After
    fun tearDown() {
        Logging.setRequestLoggingEnabled(false)
        Logging.clear()
    }

    @Test
    fun `request log headers hide credentials but keep diagnostic metadata`() {
        val headers = mapOf(
            "Authorization" to "Bearer secret-token",
            "X-Api-Key" to "secret-key",
            "Content-Type" to "application/json",
        ).toHeaders().toRedactedMap()

        assertEquals("[已隐藏]", headers["Authorization"])
        assertEquals("[已隐藏]", headers["X-Api-Key"])
        assertEquals("application/json", headers["Content-Type"])
        assertFalse(headers.values.any { it.contains("secret") })
    }

    @Test
    fun requestLogsDropPathQueryBodyAndCredentials() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok").addHeader("Set-Cookie", "session=secret"))
        server.start()
        try {
            val request = Request.Builder()
                .url(server.url("/private/search?query=raw-secret"))
                .header("Authorization", "Bearer secret-key")
                .header("Cookie", "session=secret")
                .post("{\"query\":\"raw-secret\"}".toRequestBody("application/json".toMediaType()))
                .build()
            OkHttpClient.Builder()
                .addInterceptor(RequestLoggingInterceptor())
                .build()
                .newCall(request)
                .execute()
                .close()

            val log = Logging.getRequestLogs().single()
            assertFalse(log.url.contains("private"))
            assertFalse(log.url.contains("raw-secret"))
            assertEquals("[已隐藏]", log.requestHeaders["Authorization"])
            assertEquals("[已隐藏]", log.requestHeaders["Cookie"])
            assertEquals("[已隐藏]", log.responseHeaders["Set-Cookie"])
            assertNull(log.requestBody)
        } finally {
            server.shutdown()
        }
    }
}
