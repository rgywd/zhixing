package me.rerere.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DoubaoSearchServiceTest {
    @Test
    fun requestUsesOfficialEndpointAuthenticationAndPayload() {
        val request = buildDoubaoSearchRequest(
            query = "今日 科技新闻",
            resultSize = 12,
            apiKey = "test-key",
        )

        assertEquals(DOUBAO_SEARCH_URL, request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals("application", request.body?.contentType()?.type)
        assertEquals("json", request.body?.contentType()?.subtype)

        val buffer = Buffer()
        assertNotNull(request.body)
        request.body!!.writeTo(buffer)
        val body = SearchService.json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals("今日 科技新闻", body.getValue("Query").jsonPrimitive.content)
        assertEquals("web", body.getValue("SearchType").jsonPrimitive.content)
        assertEquals(12, body.getValue("Count").jsonPrimitive.int)

        val filter = body.getValue("Filter").jsonObject
        assertTrue(filter.getValue("NeedUrl").jsonPrimitive.boolean)
        assertTrue(filter.getValue("NeedSummary").jsonPrimitive.boolean)
        assertTrue(filter.getValue("NeedContent").jsonPrimitive.boolean)
    }

    @Test
    fun responseParsesOfficialNestedResultAndPrefersSummaryThenSnippetThenContent() {
        val result = parseDoubaoSearchResponse(
            """
                {
                  "ResponseMetadata": {
                    "RequestId": "test-request"
                  },
                  "Result": {
                    "ResultCount": 4,
                    "WebResults": [
                      {
                        "Title": "Summary result",
                        "Url": "https://example.com/summary",
                        "Summary": "summary text",
                        "Snippet": "snippet text",
                        "Content": "content text"
                      },
                      {
                        "Title": "Snippet result",
                        "Url": "https://example.com/snippet",
                        "Summary": "",
                        "Snippet": "snippet fallback",
                        "Content": "content text"
                      },
                      {
                        "Title": "Content result",
                        "Url": "https://example.com/content",
                        "Content": "content fallback"
                      },
                      {
                        "Title": "Invalid result without URL",
                        "Summary": "ignored"
                      }
                    ]
                  }
                }
            """.trimIndent()
        )

        assertEquals(3, result.items.size)
        assertEquals("summary text", result.items[0].text)
        assertEquals("snippet fallback", result.items[1].text)
        assertEquals("content fallback", result.items[2].text)
    }

    @Test
    fun responseStillAcceptsLegacyTopLevelWebResults() {
        val result = parseDoubaoSearchResponse(
            """
                {
                  "WebResults": [
                    {
                      "Title": "Legacy result",
                      "Url": "https://example.com/legacy",
                      "Summary": "legacy summary"
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals(1, result.items.size)
        assertEquals("Legacy result", result.items.single().title)
    }

    @Test
    fun missingWebResultsIsReportedAsAnError() {
        try {
            parseDoubaoSearchResponse("{\"Message\":\"invalid request\"}")
            fail("Expected missing WebResults to fail")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("Result.WebResults"))
        }
    }

    @Test
    fun emptyApiKeyReturnsFailureWithoutMakingARequest() = runBlocking {
        val result = DoubaoSearchService.search(
            params = buildJsonObject { put("query", "test") },
            commonOptions = SearchCommonOptions(),
            serviceOptions = SearchServiceOptions.DoubaoOptions(),
        )

        assertTrue(result.isFailure)
        assertEquals("Doubao API key is required", result.exceptionOrNull()?.message)
    }
}
