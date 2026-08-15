package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.core.ToolExecutionException
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.ImageSearchItem
import me.rerere.search.ImageSearchResult
import me.rerere.search.ScrapedResult
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchProviderException
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.net.URI
import java.time.LocalDate
import java.util.Locale
import kotlin.uuid.Uuid

private const val RESEARCH_PURPOSE_PARAMETER = "purpose"
private const val TAG = "SearchTools"
private const val MAX_RESULT_SIZE = 20
private const val MAX_IMAGE_RESULTS = 5
private const val MAX_ITEM_TEXT_CHARS = 1_200
private const val MAX_SEARCH_OUTPUT_CHARS = 16_000
private const val MAX_SCRAPE_CONTENT_CHARS = 12_000
private const val SCRAPE_TRUNCATION_SUFFIX = "\n\n[content truncated by Zhixing]"
private const val SEARCH_FAILURE_GUIDANCE =
    "If this tool returns an error, do not call provider-native or undeclared search tools. " +
        "Continue without search and clearly explain the limitation."

private val RESEARCH_PURPOSE_SCHEMA = buildJsonObject {
    put("type", "string")
    put("maxLength", 120)
    put(
        "description",
        "Short user-visible research goal. Reuse verbatim for related search_web, search_images, and scrape_web calls."
    )
}

private val MULTI_SEARCH_PARAMETERS = querySchema()

fun createSearchTools(settings: Settings): Set<Tool> {
    val selectedOptions = settings.selectedSearchServices()
    val imageSearchers = selectedOptions.mapNotNull { options ->
        val service = SearchService.getService(options)
        service.imageParameters(options)?.let { ImageSearcher(options, service) }
    }
    val scraper = selectedOptions.firstNotNullOfOrNull { options ->
        val service = SearchService.getService(options)
        service.scrapingParameters(options)?.let { Scraper(options, service) }
    }

    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web for up-to-date or specific information.
                    Use this when the user asks for the latest news, current facts, or needs verification.
                    Generate focused keywords and run multiple searches if needed.
                    Today is ${LocalDate.now().toLocalString(true)}.

                    Response format:
                    - items[].id (short id), index, title, url, text, providers[] (search providers that returned it)
                    - images[]: legacy image urls returned by providers (may be empty)
                    - failures[]: providers that failed while other providers still returned usable results

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    Failure handling:
                    $SEARCH_FAILURE_GUIDANCE

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    """.trimIndent(),
                parameters = {
                    if (selectedOptions.size == 1) {
                        val options = selectedOptions.single()
                        SearchService.getService(options).parameters(options)
                    } else {
                        MULTI_SEARCH_PARAMETERS
                    }.withResearchPurposeParameter()
                },
                executionMode = ToolExecutionMode.PARALLEL_READ_ONLY,
                execute = { arguments ->
                    val result = executeMultiSearch(
                        params = arguments.jsonObject.withoutResearchPurpose(),
                        commonOptions = settings.searchCommonOptions,
                        options = selectedOptions,
                    )
                    listOf(UIMessagePart.Text(encodeSearchResultWithinBudget(result)))
                }
            )
        )

        if (imageSearchers.isNotEmpty()) {
            add(
                Tool(
                    name = "search_images",
                    description = """
                        Search for images using a text query when images materially help answer the user's request.
                        Only use image URLs from the returned images[] array; never fabricate or modify URLs.
                        Response items include imageUrl, optional sourceUrl/title/site/size metadata, and providers[].
                        Failure handling: $SEARCH_FAILURE_GUIDANCE
                    """.trimIndent(),
                    parameters = {
                        if (imageSearchers.size == 1) {
                            imageSearchers.single().service.imageParameters(imageSearchers.single().options)
                        } else {
                            MULTI_SEARCH_PARAMETERS
                        }.withResearchPurposeParameter()
                    },
                    executionMode = ToolExecutionMode.PARALLEL_READ_ONLY,
                    execute = { arguments ->
                        val result = executeMultiImageSearch(
                            params = arguments.jsonObject.withoutResearchPurpose(),
                            commonOptions = settings.searchCommonOptions,
                            searchers = imageSearchers,
                        )
                        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(result)))
                    },
                )
            )
        }

        if (scraper != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content using ${scraper.options.displayName}.
                        When multiple search providers are enabled, this uses the first enabled provider in settings order that supports scraping.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Failure handling: $SEARCH_FAILURE_GUIDANCE
                    """.trimIndent(),
                    parameters = {
                        scraper.service.scrapingParameters(scraper.options).withResearchPurposeParameter()
                    },
                    executionMode = ToolExecutionMode.PARALLEL_READ_ONLY,
                    execute = { arguments ->
                        val startedAt = System.nanoTime()
                        val result = withTimeoutOrNull(settings.searchCommonOptions.scrapeTimeoutMillis()) {
                            scraper.service.scrape(
                                params = arguments.jsonObject.withoutResearchPurpose(),
                                commonOptions = settings.searchCommonOptions,
                                serviceOptions = scraper.options,
                            )
                        } ?: Result.failure(SearchDeadlineExceededException())
                        logProviderResult(
                            toolName = "scrape_web",
                            providerName = scraper.options.displayName,
                            elapsedMillis = elapsedMillis(startedAt),
                            resultCount = result.getOrNull()?.urls?.size ?: 0,
                            error = result.exceptionOrNull(),
                            requestId = result.getOrNull()?.requestId
                                ?: (result.exceptionOrNull() as? SearchProviderException)?.requestId,
                        )
                        val scraped = result.getOrElse { throwable ->
                            if (throwable is SearchDeadlineExceededException) {
                                throw ToolExecutionException("SEARCH_TIMEOUT")
                            }
                            throw ToolExecutionException("SEARCH_UNAVAILABLE")
                        }
                        val payload = JsonInstantPretty.encodeToJsonElement(truncateScrapedResult(scraped)).jsonObject
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                )
            )
        }
    }
}

internal fun InputSchema?.withResearchPurposeParameter(): InputSchema.Obj {
    val base = this as? InputSchema.Obj
    return InputSchema.Obj(
        properties = buildJsonObject {
            base?.properties?.forEach { (name, schema) -> put(name, schema) }
            put(RESEARCH_PURPOSE_PARAMETER, RESEARCH_PURPOSE_SCHEMA)
        },
        required = (base?.required.orEmpty() + RESEARCH_PURPOSE_PARAMETER).distinct(),
    )
}

internal fun JsonObject.withoutResearchPurpose(): JsonObject =
    JsonObject(filterKeys { it != RESEARCH_PURPOSE_PARAMETER })

internal fun Settings.selectedSearchServices(): List<SearchServiceOptions> {
    val selected = searchServices.filter { it.id in searchServiceSelectedIds }
    return selected.ifEmpty { listOf(searchServices.firstOrNull() ?: SearchServiceOptions.DEFAULT) }
}

internal suspend fun executeMultiSearch(
    params: JsonObject,
    commonOptions: SearchCommonOptions,
    options: List<SearchServiceOptions>,
    timeoutMillis: Long = commonOptions.searchTimeoutMillis(),
    search: suspend (SearchServiceOptions, JsonObject, SearchCommonOptions) -> Result<SearchResult> =
        { serviceOptions, searchParams, searchCommonOptions ->
            SearchService.getService(serviceOptions).search(searchParams, searchCommonOptions, serviceOptions)
        },
): SearchToolResult {
    val boundedOptions = commonOptions.copy(resultSize = commonOptions.resultSize.coerceIn(1, MAX_RESULT_SIZE))
    val outcomes = collectProviderOutcomes(
        options = options,
        timeoutMillis = timeoutMillis,
    ) { serviceOptions -> search(serviceOptions, params, boundedOptions) }
    return aggregateSearchResults(
        outcomes = outcomes,
        resultLimit = commonOptions.resultSize.coerceIn(1, MAX_RESULT_SIZE),
    )
}

internal suspend fun executeMultiImageSearch(
    params: JsonObject,
    commonOptions: SearchCommonOptions,
    searchers: List<ImageSearcher>,
    search: suspend (ImageSearcher, JsonObject, SearchCommonOptions) -> Result<ImageSearchResult> =
        { searcher, searchParams, searchCommonOptions ->
            searcher.service.searchImages(searchParams, searchCommonOptions, searcher.options)
        },
): ImageSearchToolResult {
    val boundedOptions = commonOptions.copy(resultSize = commonOptions.resultSize.coerceIn(1, MAX_IMAGE_RESULTS))
    val outcomes = collectImageOutcomes(
        searchers = searchers,
        timeoutMillis = commonOptions.searchTimeoutMillis(),
    ) { searcher -> search(searcher, params, boundedOptions) }
    return aggregateImageResults(outcomes)
}

private suspend fun collectProviderOutcomes(
    options: List<SearchServiceOptions>,
    timeoutMillis: Long,
    execute: suspend (SearchServiceOptions) -> Result<SearchResult>,
): List<ProviderSearchOutcome> = supervisorScope {
    val channel = Channel<ProviderSearchOutcome>(Channel.UNLIMITED)
    val jobs = options.associateWith { serviceOptions ->
        launch {
            val startedAt = System.nanoTime()
            val result = executeSafely { execute(serviceOptions) }
            logProviderResult(
                toolName = "search_web",
                providerName = serviceOptions.displayName,
                elapsedMillis = elapsedMillis(startedAt),
                resultCount = result.getOrNull()?.items?.size ?: 0,
                error = result.exceptionOrNull(),
                requestId = result.getOrNull()?.requestId
                    ?: (result.exceptionOrNull() as? SearchProviderException)?.requestId,
            )
            channel.send(ProviderSearchOutcome(serviceOptions.asProvider(), result))
        }
    }
    val outcomes = mutableListOf<ProviderSearchOutcome>()
    withTimeoutOrNull(timeoutMillis) {
        repeat(options.size) { outcomes += channel.receive() }
    }
    jobs.values.forEach { it.cancel() }
    jobs.values.joinAll()
    while (true) {
        val queued = channel.tryReceive().getOrNull() ?: break
        outcomes += queued
    }
    val completedIds = outcomes.mapTo(mutableSetOf()) { it.provider.id }
    options.filter { it.id.toString() !in completedIds }.forEach { optionsTimedOut ->
        logProviderResult("search_web", optionsTimedOut.displayName, timeoutMillis, 0, SearchDeadlineExceededException())
        outcomes += ProviderSearchOutcome(
            provider = optionsTimedOut.asProvider(),
            result = Result.failure(SearchDeadlineExceededException()),
        )
    }
    channel.close()
    outcomes
}

private suspend fun collectImageOutcomes(
    searchers: List<ImageSearcher>,
    timeoutMillis: Long,
    execute: suspend (ImageSearcher) -> Result<ImageSearchResult>,
): List<ProviderImageOutcome> = supervisorScope {
    val channel = Channel<ProviderImageOutcome>(Channel.UNLIMITED)
    val jobs = searchers.associateWith { searcher ->
        launch {
            val startedAt = System.nanoTime()
            val result = executeSafely { execute(searcher) }
            logProviderResult(
                toolName = "search_images",
                providerName = searcher.options.displayName,
                elapsedMillis = elapsedMillis(startedAt),
                resultCount = result.getOrNull()?.items?.size ?: 0,
                error = result.exceptionOrNull(),
                requestId = result.getOrNull()?.requestId
                    ?: (result.exceptionOrNull() as? SearchProviderException)?.requestId,
            )
            channel.send(ProviderImageOutcome(searcher.options.asProvider(), result))
        }
    }
    val outcomes = mutableListOf<ProviderImageOutcome>()
    withTimeoutOrNull(timeoutMillis) {
        repeat(searchers.size) { outcomes += channel.receive() }
    }
    jobs.values.forEach { it.cancel() }
    jobs.values.joinAll()
    while (true) {
        val queued = channel.tryReceive().getOrNull() ?: break
        outcomes += queued
    }
    val completedIds = outcomes.mapTo(mutableSetOf()) { it.provider.id }
    searchers.filter { it.options.id.toString() !in completedIds }.forEach { searcher ->
        logProviderResult("search_images", searcher.options.displayName, timeoutMillis, 0, SearchDeadlineExceededException())
        outcomes += ProviderImageOutcome(
            provider = searcher.options.asProvider(),
            result = Result.failure(SearchDeadlineExceededException()),
        )
    }
    channel.close()
    outcomes
}

private suspend fun <T> executeSafely(block: suspend () -> Result<T>): Result<T> = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (throwable: Throwable) {
    Result.failure(throwable)
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L

private fun logProviderResult(
    toolName: String,
    providerName: String,
    elapsedMillis: Long,
    resultCount: Int,
    error: Throwable?,
    requestId: String? = null,
) {
    val code = when (error) {
        null -> "OK"
        is SearchDeadlineExceededException -> "TIMEOUT"
        is SearchProviderException -> error.code
        else -> "UPSTREAM_ERROR"
    }
    val requestPart = requestId?.takeIf(String::isNotBlank)?.let { " requestId=${it.take(96)}" }.orEmpty()
    runCatching {
        Log.i(
            TAG,
            "tool=$toolName provider=$providerName elapsedMs=$elapsedMillis results=$resultCount code=$code$requestPart",
        )
    }
}

internal fun aggregateSearchResults(
    outcomes: List<ProviderSearchOutcome>,
    resultLimit: Int = MAX_RESULT_SIZE,
): SearchToolResult {
    val successful = outcomes.mapNotNull { outcome ->
        outcome.result.getOrNull()?.let { outcome.provider to it }
    }
    if (successful.isEmpty()) throw ToolExecutionException("SEARCH_UNAVAILABLE")

    val mergedItems = linkedMapOf<String, MutableSearchItem>()
    val maxResultCount = successful.maxOfOrNull { (_, result) -> result.items.size } ?: 0
    repeat(maxResultCount) { itemIndex ->
        successful.forEach { (provider, result) ->
            val item = result.items.getOrNull(itemIndex) ?: return@forEach
            val normalizedUrl = normalizeSearchResultUrl(item.url)
            val key = normalizedUrl.ifBlank { "${provider.id}:$itemIndex" }
            val existing = mergedItems[key]
            if (existing == null) {
                mergedItems[key] = MutableSearchItem(
                    title = item.title,
                    url = item.url,
                    text = item.text.take(MAX_ITEM_TEXT_CHARS),
                    providers = linkedSetOf(provider),
                )
            } else {
                existing.providers += provider
            }
        }
    }

    val answers = successful.mapNotNull { (provider, result) ->
        result.answer?.takeIf(String::isNotBlank)?.let { "[${provider.name}] ${it.take(4_000)}" }
    }
    val images = successful.flatMap { (_, result) -> result.images }.filter(::isSafeRemoteUrl).distinct().take(5)
    return SearchToolResult(
        answer = answers.takeIf(List<String>::isNotEmpty)?.joinToString("\n\n"),
        items = mergedItems.values.take(resultLimit.coerceIn(1, MAX_RESULT_SIZE)).mapIndexed { index, item ->
            SearchToolResultItem(
                id = Uuid.random().toString().take(6),
                index = index + 1,
                title = item.title,
                url = item.url,
                text = item.text,
                providers = item.providers.toList(),
            )
        },
        images = images,
        failures = outcomes.mapNotNull(::failureFor),
    )
}

private fun aggregateImageResults(outcomes: List<ProviderImageOutcome>): ImageSearchToolResult {
    val successful = outcomes.mapNotNull { outcome ->
        outcome.result.getOrNull()?.let { outcome.provider to it }
    }
    if (successful.isEmpty()) throw ToolExecutionException("SEARCH_UNAVAILABLE")

    val merged = linkedMapOf<String, MutableImageItem>()
    successful.forEach { (provider, result) ->
        result.items.forEach { item ->
            if (!isSafeRemoteUrl(item.imageUrl)) return@forEach
            val key = normalizeSearchResultUrl(item.imageUrl)
            val existing = merged[key]
            if (existing == null) {
                merged[key] = MutableImageItem(item, linkedSetOf(provider))
            } else {
                existing.providers += provider
            }
        }
    }
    val items = merged.values.take(MAX_IMAGE_RESULTS).mapIndexed { index, item ->
        ImageSearchToolResultItem(
            id = Uuid.random().toString().take(6),
            index = index + 1,
            imageUrl = item.item.imageUrl,
            sourceUrl = item.item.sourceUrl?.takeIf(::isSafeRemoteUrl),
            title = item.item.title,
            siteName = item.item.siteName,
            width = item.item.width,
            height = item.item.height,
            shape = item.item.shape,
            rankScore = item.item.rankScore,
            watermark = item.item.watermark,
            blurDescription = item.item.blurDescription,
            providers = item.providers.toList(),
        )
    }
    return ImageSearchToolResult(
        items = items,
        images = items.map(ImageSearchToolResultItem::imageUrl),
        failures = outcomes.mapNotNull(::failureFor),
    )
}

private fun failureFor(outcome: ProviderSearchOutcome): SearchProviderFailure? =
    outcome.result.exceptionOrNull()?.let { failureFor(outcome.provider, it) }

private fun failureFor(outcome: ProviderImageOutcome): SearchProviderFailure? =
    outcome.result.exceptionOrNull()?.let { failureFor(outcome.provider, it) }

private fun failureFor(provider: SearchProvider, throwable: Throwable) = SearchProviderFailure(
    provider = provider,
    code = when (throwable) {
        is SearchDeadlineExceededException -> "TIMEOUT"
        is SearchProviderException -> throwable.code
        else -> "UPSTREAM_ERROR"
    },
    message = if (throwable is SearchDeadlineExceededException) "Provider exceeded the configured deadline" else "Provider request failed",
)

internal fun normalizeSearchResultUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return trimmed
    return runCatching {
        val uri = URI(trimmed).normalize()
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching trimmed.substringBefore('#').trimEnd('/')
        val port = when {
            scheme == "http" && uri.port == 80 -> -1
            scheme == "https" && uri.port == 443 -> -1
            else -> uri.port
        }
        val path = (uri.rawPath ?: "").let { if (it.length > 1) it.trimEnd('/') else it }
        URI(scheme, uri.rawUserInfo, host, port, path, uri.rawQuery, null).toASCIIString()
    }.getOrElse { trimmed.substringBefore('#').trimEnd('/') }
}

private fun isSafeRemoteUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)

private fun encodeSearchResultWithinBudget(result: SearchToolResult): String {
    var candidate = result
    var encoded = JsonInstantPretty.encodeToString(candidate)
    while (encoded.length > MAX_SEARCH_OUTPUT_CHARS && candidate.items.size > 1) {
        candidate = candidate.copy(items = candidate.items.dropLast(1))
        encoded = JsonInstantPretty.encodeToString(candidate)
    }
    if (encoded.length > MAX_SEARCH_OUTPUT_CHARS) {
        candidate = candidate.copy(answer = candidate.answer?.take(1_000), images = candidate.images.take(2))
        encoded = JsonInstantPretty.encodeToString(candidate)
    }
    if (encoded.length > MAX_SEARCH_OUTPUT_CHARS) {
        candidate = candidate.copy(
            answer = null,
            items = candidate.items.take(1).map { it.copy(text = it.text.take(200)) },
            images = emptyList(),
        )
        encoded = JsonInstantPretty.encodeToString(candidate)
    }
    return encoded
}

private fun truncateScrapedResult(result: ScrapedResult): ScrapedResult {
    var remaining = MAX_SCRAPE_CONTENT_CHARS
    return result.copy(
        urls = result.urls.map { item ->
            val content = when {
                item.content.length <= remaining -> item.content
                remaining <= SCRAPE_TRUNCATION_SUFFIX.length -> SCRAPE_TRUNCATION_SUFFIX.take(remaining)
                else -> item.content.take(remaining - SCRAPE_TRUNCATION_SUFFIX.length) + SCRAPE_TRUNCATION_SUFFIX
            }
            remaining = (remaining - content.length).coerceAtLeast(0)
            item.copy(content = content)
        }
    )
}

private fun querySchema() = InputSchema.Obj(
    properties = buildJsonObject {
        put("query", buildJsonObject {
            put("type", "string")
            put("description", "search keyword")
        })
    },
    required = listOf("query"),
)

private fun SearchServiceOptions.asProvider() = SearchProvider(id.toString(), displayName)

internal data class ImageSearcher(
    val options: SearchServiceOptions,
    val service: SearchService<SearchServiceOptions>,
)

private data class Scraper(
    val options: SearchServiceOptions,
    val service: SearchService<SearchServiceOptions>,
)

private data class MutableSearchItem(
    val title: String,
    val url: String,
    val text: String,
    val providers: LinkedHashSet<SearchProvider>,
)

private data class MutableImageItem(
    val item: ImageSearchItem,
    val providers: LinkedHashSet<SearchProvider>,
)

internal data class ProviderSearchOutcome(
    val provider: SearchProvider,
    val result: Result<SearchResult>,
)

internal data class ProviderImageOutcome(
    val provider: SearchProvider,
    val result: Result<ImageSearchResult>,
)

private class SearchDeadlineExceededException : Exception("SEARCH_TIMEOUT")

@Serializable
internal data class SearchProvider(
    val id: String,
    val name: String,
)

@Serializable
internal data class SearchProviderFailure(
    val provider: SearchProvider,
    val code: String,
    val message: String,
)

@Serializable
internal data class SearchToolResult(
    val answer: String? = null,
    val items: List<SearchToolResultItem>,
    val images: List<String> = emptyList(),
    val failures: List<SearchProviderFailure> = emptyList(),
)

@Serializable
internal data class SearchToolResultItem(
    val id: String,
    val index: Int,
    val title: String,
    val url: String,
    val text: String,
    val providers: List<SearchProvider>,
)

@Serializable
internal data class ImageSearchToolResult(
    val items: List<ImageSearchToolResultItem>,
    val images: List<String>,
    val failures: List<SearchProviderFailure> = emptyList(),
)

@Serializable
internal data class ImageSearchToolResultItem(
    val id: String,
    val index: Int,
    val imageUrl: String,
    val sourceUrl: String? = null,
    val title: String? = null,
    val siteName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val shape: String? = null,
    val rankScore: Double? = null,
    val watermark: Boolean? = null,
    val blurDescription: String? = null,
    val providers: List<SearchProvider>,
)
