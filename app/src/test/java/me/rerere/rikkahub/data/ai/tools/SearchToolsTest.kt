package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchCommonOptions
import me.rerere.search.ImageSearchItem
import me.rerere.search.ImageSearchResult
import me.rerere.search.SearchService
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
    fun `image capable providers expose image search with purpose`() {
        val searxng: SearchServiceOptions = SearchServiceOptions.SearXNGOptions(url = "https://search.example.com")
        val settings = Settings(
            searchServices = listOf(searxng),
            searchServiceSelectedIds = setOf(searxng.id),
        )
        val tool = createSearchTools(settings).single { it.name == "search_images" }
        val schema = tool.parameters() as InputSchema.Obj

        assertTrue(schema.required.orEmpty().containsAll(listOf("query", "purpose")))
    }

    @Test
    fun `AnySearch does not expose stock-library results as image search`() {
        val anySearch: SearchServiceOptions = SearchServiceOptions.AnySearchOptions()
        val settings = Settings(
            searchServices = listOf(anySearch),
            searchServiceSelectedIds = setOf(anySearch.id),
        )

        assertTrue(createSearchTools(settings).none { it.name == "search_images" })
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
    fun `search tools opt into run scoped deduplication without purpose`() {
        val searxng: SearchServiceOptions = SearchServiceOptions.SearXNGOptions(url = "https://search.example.com")
        val jina: SearchServiceOptions = SearchServiceOptions.JinaOptions()
        val tools = createSearchTools(
            Settings(
                searchServices = listOf(searxng, jina),
                searchServiceSelectedIds = setOf(searxng.id, jina.id),
            )
        )

        listOf("search_web", "search_images", "scrape_web").forEach { toolName ->
            val tool = tools.single { it.name == toolName }
            assertEquals(ToolExecutionMode.PARALLEL_READ_ONLY, tool.executionMode)
            assertTrue(tool.deduplicateWithinRun)
            assertEquals(setOf("purpose"), tool.deduplicationIgnoredInputFields)
        }
    }

    @Test
    fun `search tool descriptions keep provider-native fallback out of normal execution`() {
        val searxng: SearchServiceOptions = SearchServiceOptions.SearXNGOptions(url = "https://search.example.com")
        val jina: SearchServiceOptions = SearchServiceOptions.JinaOptions()
        val settings = Settings(
            searchServices = listOf(searxng, jina),
            searchServiceSelectedIds = setOf(searxng.id, jina.id),
        )

        listOf("search_web", "search_images", "scrape_web").forEach { toolName ->
            val description = createSearchTools(settings).single { it.name == toolName }.description
            assertTrue(description.contains("If this tool returns an error"))
            assertTrue(description.contains("do not call provider-native or undeclared search tools"))
            assertTrue(description.contains("Continue without search"))
        }
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
    fun `deadline returns completed provider and cancels slow provider`() = runBlocking {
        val slowCancelled = CompletableDeferred<Unit>()
        val result = executeMultiSearch(
            params = params,
            commonOptions = SearchCommonOptions(),
            options = listOf(first, second),
            search = { options, _, _ ->
                if (options.id == first.id) {
                    Result.success(resultFor(options.displayName))
                } else {
                    try {
                        awaitCancellation()
                    } finally {
                        slowCancelled.complete(Unit)
                    }
                }
            },
            timeoutMillis = 50,
        )

        assertEquals(1, result.items.size)
        assertEquals("TIMEOUT", result.failures.single().code)
        withTimeout(1_000) { slowCancelled.await() }
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
        assertEquals("SEARCH_UNAVAILABLE", error.message)
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

    @Test
    fun `aggregate applies global result and text limits`() {
        val provider = SearchProvider(first.id.toString(), first.displayName)
        val result = aggregateSearchResults(
            outcomes = listOf(
                ProviderSearchOutcome(
                    provider,
                    Result.success(
                        SearchResult(
                            items = (1..5).map { item("Item $it", "https://example.com/$it").copy(text = "x".repeat(2_000)) }
                        )
                    )
                )
            ),
            resultLimit = 2,
        )

        assertEquals(2, result.items.size)
        assertEquals(1_200, result.items.single { it.index == 1 }.text.length)
    }

    @Test
    fun `image aggregation keeps metadata and caps global images`() = runBlocking {
        val searxng: SearchServiceOptions = SearchServiceOptions.SearXNGOptions(url = "https://search.example.com")
        val searcher = ImageSearcher(searxng, SearchService.getService(searxng))
        val result = executeMultiImageSearch(
            params = params,
            commonOptions = SearchCommonOptions(resultSize = 20),
            searchers = listOf(searcher),
        ) { _, _, options ->
            assertEquals(5, options.resultSize)
            Result.success(
                ImageSearchResult(
                    items = (1..6).map { index ->
                        ImageSearchItem(
                            imageUrl = "https://img.example.com/$index.jpg",
                            sourceUrl = "https://example.com/$index",
                            title = "Image $index",
                            width = 1200,
                            height = 800,
                        )
                    }
                )
            )
        }

        assertEquals(5, result.items.size)
        assertEquals(1200, result.items.first().width)
        assertEquals(result.items.map { it.imageUrl }, result.images)
    }

    private fun resultFor(provider: String) = SearchResult(
        items = listOf(item(provider, "https://${provider.hashCode()}.example/result"))
    )

    private fun item(title: String, url: String) = SearchResult.SearchResultItem(title, url, "$title text")
}
