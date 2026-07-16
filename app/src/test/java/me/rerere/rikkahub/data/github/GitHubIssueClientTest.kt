package me.rerere.rikkahub.data.github

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class GitHubIssueClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `creates issue with GitHub API contract`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"number":42,"html_url":"https://github.com/rgywd/zhixing/issues/42"}"""
        ))
        val client = GitHubIssueClient(
            client = OkHttpClient(),
            endpoint = server.url("/repos/rgywd/zhixing/issues").toString(),
        )

        val created = client.createIssue(
            token = "test-token",
            title = "feat: direct submit",
            body = "body",
            label = "enhancement",
        )

        assertEquals(42, created.number)
        assertEquals("https://github.com/rgywd/zhixing/issues/42", created.url)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("feat: direct submit", body.getValue("title").jsonPrimitive.content)
        assertEquals("enhancement", body.getValue("labels").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `maps authorization failure without exposing response body`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret response"))
        val client = GitHubIssueClient(
            client = OkHttpClient(),
            endpoint = server.url("/repos/rgywd/zhixing/issues").toString(),
        )

        val error = assertThrows(GitHubIssueApiException::class.java) {
            runBlocking {
                client.createIssue("bad-token", "title", "body", "bug")
            }
        }

        assertEquals(401, error.statusCode)
        assertEquals(false, error.message.orEmpty().contains("secret response"))
    }
}
