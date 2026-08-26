package me.rerere.search

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.decodeFromString

class AnySearchServiceTest {
    @Test
    fun searchRequestUsesMaxResultsWithoutProviderSpecificImageTag() {
        val request = buildAnySearchRequest(
            query = "北京医保新规",
            resultSize = 99,
            apiKey = "secret",
        )
        val body = request.bodyJson()

        assertEquals(ANYSEARCH_SEARCH_URL, request.url.toString())
        assertEquals("Bearer secret", request.header("Authorization"))
        assertEquals("北京医保新规", body.getValue("query").jsonPrimitive.content)
        assertEquals(20, body.getValue("max_results").jsonPrimitive.int)
        assertFalse(body.containsKey("tag"))
        assertFalse(body.containsKey("limit"))
    }

    @Test
    fun anonymousSearchOmitsAuthorization() {
        val request = buildAnySearchRequest("test", 0, null)
        assertNull(request.header("Authorization"))
        assertEquals(1, request.bodyJson().getValue("max_results").jsonPrimitive.int)
    }

    @Test
    fun extractRequestUsesOfficialMcpToolCallShape() {
        val request = buildAnySearchExtractRequest("https://example.com/page", "secret")
        val body = request.bodyJson()

        assertEquals(ANYSEARCH_MCP_URL, request.url.toString())
        assertEquals("2.0", body.getValue("jsonrpc").jsonPrimitive.content)
        assertEquals("tools/call", body.getValue("method").jsonPrimitive.content)
        val params = body.getValue("params").jsonObject
        assertEquals("extract", params.getValue("name").jsonPrimitive.content)
        assertEquals(
            "https://example.com/page",
            params.getValue("arguments").jsonObject.getValue("url").jsonPrimitive.content,
        )
    }

    @Test
    fun searchResponseValidatesBusinessCodeAndKeepsContent() {
        val response = parseAnySearchResponse(
            """{"code":0,"data":{"results":[{"title":"Title","url":"https://example.com","snippet":"short","content":"full"}]}}"""
        )
        assertEquals("full", response.data.results.single().content)

        val error = assertThrows(SearchProviderException::class.java) {
            parseAnySearchResponse("""{"code":429,"message":"sensitive upstream text","request_id":"req-1"}""")
        }
        assertEquals("429", error.code)
        assertEquals("req-1", error.requestId)
    }

    @Test
    fun extractResponseReadsTextAndRejectsJsonRpcErrors() {
        val content = parseAnySearchExtractResponse(
            """{"result":{"content":[{"type":"text","text":"# Extracted"}]}}"""
        )
        assertEquals("# Extracted", content)

        val error = assertThrows(SearchProviderException::class.java) {
            parseAnySearchExtractResponse("""{"error":{"code":-32000,"message":"secret"}}""")
        }
        assertEquals("-32000", error.code)
        assertFalse(error.message.orEmpty().contains("secret"))
    }

    @Test
    fun oldCommonOptionsJsonUsesNewTimeoutDefaults() {
        val options = SearchService.json.decodeFromString<SearchCommonOptions>("""{"resultSize":7}""")
        assertEquals(7, options.resultSize)
        assertEquals(8, options.searchTimeoutSeconds)
        assertEquals(15, options.scrapeTimeoutSeconds)
    }

    private fun okhttp3.Request.bodyJson() = Buffer().also { body!!.writeTo(it) }
        .readUtf8()
        .let(SearchService.json::parseToJsonElement)
        .jsonObject
}
