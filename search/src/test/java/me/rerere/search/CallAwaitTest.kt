package me.rerere.search

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class CallAwaitTest {
    @Test
    fun cancellingCoroutineCancelsUnderlyingHttpCall() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeadersDelay(30, TimeUnit.SECONDS)
                .setBody("slow")
        )
        server.start()
        try {
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/slow")).build())
            val job = async(start = CoroutineStart.UNDISPATCHED) { call.await().close() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            server.shutdown()
        }
    }
}
