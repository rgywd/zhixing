package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SearchToolsTest {
    private val first = SearchServiceOptions.BingLocalOptions()
    private val second = SearchServiceOptions.DoubaoOptions(apiKey = "test")
    private val params = buildJsonObject { put("query", "multi source") }

    @Test
    fun `search and scrape schemas require a user-visible purpose`() {
        val jina = SearchServiceOptions.JinaOptions()
        val settings = Settings(
            searchServices = listOf(jina),
            searchServiceSelectedIds = setOf(jina.id),
        )
        val tools = createSearchTools(settings)

        mapOf(
            "search_web" to "query",
            "scrape_web" to "url",
        ).forEach { (toolName, providerParameter) ->
            val schema = tools.single { it.name == toolName }.parameters() as InputSchema.Obj
            assertTrue(schema.properties.containsKey("purpose"))
            assertTrue(schema.required.orEmpty().contains("purpose"))
            assertTrue(schema.properties.containsKey(providerParameter))
            assertTrue(schema.required.orEmpty().contains(providerParameter))
        }
    }

    @Test
    fun `purpose is removed before provider execution`() {
        val providerParams = buildJsonObject {
            put("query", "campus")
            put("purpose", "Confirm the campus")
        }.withoutResearchPurpose()

        assertEquals(setOf("query"), providerParams.keys)
        assertEquals("campus", providerParams["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun `selected providers start concurrently`() = runBlocking {
        val startedCount = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val execution = async {
            executeMultiSearch(params, SearchCommonOptions(), listOf(first, second)) { options, _, _ ->
                if (startedCount.incrementAndGet() == 2) bothStarted.complete(Unit)
                release.await()
                Result.success(resultFor(options.displayName))
            }
        }

        withTimeout(1_000) { bothStarted.await() }
        release.complete(Unit)
        assertEquals(2, execution.await().items.size)
    }

    @Test
    fun `one provider failure keeps successful results`() = runBlocking {
        val result = executeMultiSearch(params, SearchCommonOptions(), listOf(first, second)) { options, _, _ ->
            if (options.id == first.id) Result.failure(IllegalStateException("blocked"))
            else Result.success(resultFor(options.displayName))
        }

        assertEquals(1, result.items.size)
        assertEquals(second.id.toString(), result.items.single().providers.single().id)
        assertEquals(first.id.toString(), result.failures.single().provider.id)
    }

    @Test
    fun `all provider failures surface an aggregate error`() {
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                executeMultiSearch(params, SearchCommonOptions(), listOf(first, second)) { _, _, _ ->
                    Result.failure(IllegalStateException("unavailable"))
                }
            }
        }
        assertTrue(error.message.orEmpty().contains("Bing"))
        assertTrue(error.message.orEmpty().contains("豆包搜索"))
    }

    @Test
    fun `duplicate normalized urls merge provider identities`() {
        val firstProvider = SearchProvider(first.id.toString(), first.displayName)
        val secondProvider = SearchProvider(second.id.toString(), second.displayName)
        val result = aggregateSearchResults(
            listOf(
                ProviderSearchOutcome(firstProvider, Result.success(SearchResult(items = listOf(
                    item("First", "HTTPS://Example.COM/path/#fragment"),
                    item("First extra", "https://first.example/result"),
                )))),
                ProviderSearchOutcome(secondProvider, Result.success(SearchResult(items = listOf(
                    item("Duplicate", "https://example.com/path"),
                    item("Second extra", "https://second.example/result"),
                )))),
            )
        )

        assertEquals(3, result.items.size)
        assertEquals(listOf(firstProvider, secondProvider), result.items.first().providers)
        assertEquals("First extra", result.items[1].title)
        assertEquals("Second extra", result.items[2].title)
    }

    private fun resultFor(provider: String) = SearchResult(
        items = listOf(item(provider, "https://${provider.hashCode()}.example/result"))
    )

    private fun item(title: String, url: String) = SearchResult.SearchResultItem(title, url, "$title text")
}
