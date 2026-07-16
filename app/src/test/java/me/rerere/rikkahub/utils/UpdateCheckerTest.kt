package me.rerere.rikkahub.utils

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppIdentity
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `reads zhixing release manifest with product user agent`() = runBlocking {
        var userAgent = ""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                userAgent = chain.request().header("User-Agent").orEmpty()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{
                          "version":"0.2.0",
                          "publishedAt":"2026-07-16T00:00:00Z",
                          "changelog":"Update test",
                          "downloads":[{
                            "name":"zhixing-0.2.0-universal.apk",
                            "url":"https://github.com/rgywd/zhixing/releases/download/v0.2.0/zhixing-0.2.0-universal.apk",
                            "size":"100 MiB",
                            "sha256":"abc123"
                          }]
                        }""".trimIndent().toResponseBody(),
                    )
                    .build()
            }
            .build()

        val state = UpdateChecker(client).checkUpdate().first() as UiState.Success<UpdateInfo>

        assertEquals("0.2.0", state.data.version)
        assertEquals("abc123", state.data.downloads.single().sha256)
        assertTrue(userAgent.startsWith("${AppIdentity.userAgentProduct}/"))
    }

    @Test
    fun `missing release feed is a silent no-op`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("".toResponseBody())
                    .build()
            }
            .build()

        val state = UpdateChecker(client).checkUpdate().first()

        assertTrue(state is UiState.Success)
        assertTrue((state as UiState.Success<UpdateInfo>).data.downloads.isEmpty())
    }
}
