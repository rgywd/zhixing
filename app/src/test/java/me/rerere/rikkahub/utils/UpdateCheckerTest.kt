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
        val userAgents = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                userAgents += chain.request().header("User-Agent").orEmpty()
                val body = if (chain.request().url.toString() == PRODUCTION_FEED) {
                    """{"assets":[{"name":"latest.json","browser_download_url":"$MANIFEST_URL"}]}"""
                } else {
                    assertEquals(MANIFEST_URL, chain.request().url.toString())
                    """{
                      "version":"0.2.0",
                      "publishedAt":"2026-07-16T00:00:00Z",
                      "changelog":"Update test",
                      "downloads":[{
                        "name":"zhixing-0.2.0-universal.apk",
                        "url":"https://gitee.com/rongguiyewd/zhixing/releases/download/v0.2.0/zhixing-0.2.0-universal.apk",
                        "size":"100 MiB",
                        "sha256":"abc123"
                      }]
                    }""".trimIndent()
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()

        val state = UpdateChecker(client, PRODUCTION_FEED).checkUpdate().first() as UiState.Success<UpdateInfo>

        assertEquals("0.2.0", state.data.version)
        assertEquals("abc123", state.data.downloads.single().sha256)
        assertEquals(2, userAgents.size)
        assertTrue(userAgents.all { it.startsWith("${AppIdentity.userAgentProduct}/") })
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

        val state = UpdateChecker(client, PRODUCTION_FEED).checkUpdate().first()

        assertTrue(state is UiState.Success)
        assertTrue((state as UiState.Success<UpdateInfo>).data.downloads.isEmpty())
    }

    private companion object {
        const val PRODUCTION_FEED =
            "https://gitee.com/api/v5/repos/rongguiyewd/zhixing/releases/latest"
        const val MANIFEST_URL =
            "https://gitee.com/rongguiyewd/zhixing/releases/download/v0.2.0/latest.json"
    }
}
