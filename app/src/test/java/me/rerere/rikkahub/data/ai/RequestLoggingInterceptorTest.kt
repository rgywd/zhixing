package me.rerere.rikkahub.data.ai

import okhttp3.Headers.Companion.toHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestLoggingInterceptorTest {
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
}
