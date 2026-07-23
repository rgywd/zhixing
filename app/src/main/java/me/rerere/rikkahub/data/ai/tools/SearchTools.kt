package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchResult
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.net.URI
import java.time.LocalDate
import java.util.Locale
import kotlin.uuid.Uuid

private const val RESEARCH_PURPOSE_PARAMETER = "purpose"

private val RESEARCH_PURPOSE_SCHEMA = buildJsonObject {
    put("type", "string")
    put("maxLength", 120)
    put(
        "description",
        "Short user-visible research goal. Reuse verbatim for related search_web and scrape_web calls."
    )
}

private val MULTI_SEARCH_PARAMETERS = InputSchema.Obj(
    properties = buildJsonObject {
        put("query", buildJsonObject {
            put("type", "string")
            put("description", "search keyword")
        })
    },
    required = listOf("query"),
)

fun createSearchTools(settings: Settings): Set<Tool> {
    val selectedOptions = settings.selectedSearchServices()
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
                    - images[]: image urls related to the query (may be empty)
                    - failures[]: providers that failed while other providers still returned usable results

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    Images:
                    - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
                    - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
                    - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
                    """.trimIndent(),
                parameters = {
                    if (selectedOptions.size == 1) {
                        val options = selectedOptions.single()
                        SearchService.getService(options).parameters(options)
                    } else {
                        MULTI_SEARCH_PARAMETERS
                    }.withResearchPurposeParameter()
                },
                execute = { arguments ->
                    val result = executeMultiSearch(
                        params = arguments.jsonObject.withoutResearchPurpose(),
                        commonOptions = settings.searchCommonOptions,
                        options = selectedOptions,
                    )
                    listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(result)))
                }
            )
        )

        if (scraper != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content using ${scraper.options.displayName}.
                        When multiple search providers are enabled, this uses the first enabled provider in settings order that supports scraping.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    parameters = {
                        scraper.service.scrapingParameters(scraper.options).withResearchPurposeParameter()
                    },
                    execute = { arguments ->
                        val result = scraper.service.scrape(
                            params = arguments.jsonObject.withoutResearchPurpose(),
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = scraper.options,
                        )
                        val payload = JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject
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
    search: suspend (SearchServiceOptions, JsonObject, SearchCommonOptions) -> Result<SearchResult> =
        { serviceOptions, searchParams, searchCommonOptions ->
            SearchService.getService(serviceOptions).search(
                params = searchParams,
                commonOptions = searchCommonOptions,
                serviceOptions = serviceOptions,
            )
        },
): SearchToolResult = supervisorScope {
    val outcomes = options.map { serviceOptions ->
        async {
            val result = try {
                search(serviceOptions, params, commonOptions)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Result.failure(throwable)
            }
            ProviderSearchOutcome(
                provider = SearchProvider(
                    id = serviceOptions.id.toString(),
                    name = serviceOptions.displayName,
                ),
                result = result,
            )
        }
    }.awaitAll()

    aggregateSearchResults(outcomes)
}

internal fun aggregateSearchResults(outcomes: List<ProviderSearchOutcome>): SearchToolResult {
    val successful = outcomes.mapNotNull { outcome ->
        outcome.result.getOrNull()?.let { outcome.provider to it }
    }
    if (successful.isEmpty()) {
        val details = outcomes.joinToString("; ") { outcome ->
            "${outcome.provider.name}: ${outcome.result.exceptionOrNull()?.message ?: "unknown error"}"
        }
        error("All search providers failed${details.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
    }

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
                    text = item.text,
                    providers = linkedSetOf(provider),
                )
            } else {
                existing.providers += provider
            }
        }
    }

    val answers = successful.mapNotNull { (provider, result) ->
        result.answer?.takeIf { it.isNotBlank() }?.let { "[${provider.name}] $it" }
    }
    val images = successful.flatMap { (_, result) -> result.images }.distinct()
    val failures = outcomes.mapNotNull { outcome ->
        outcome.result.exceptionOrNull()?.let { throwable ->
            SearchProviderFailure(
                provider = outcome.provider,
                message = throwable.message ?: throwable::class.simpleName ?: "Unknown error",
            )
        }
    }

    return SearchToolResult(
        answer = answers.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
        items = mergedItems.values.mapIndexed { index, item ->
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
        failures = failures,
    )
}

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
        val path = (uri.rawPath ?: "").let { value ->
            if (value.length > 1) value.trimEnd('/') else value
        }
        URI(scheme, uri.rawUserInfo, host, port, path, uri.rawQuery, null).toASCIIString()
    }.getOrElse {
        trimmed.substringBefore('#').trimEnd('/')
    }
}

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

internal data class ProviderSearchOutcome(
    val provider: SearchProvider,
    val result: Result<SearchResult>,
)

@Serializable
internal data class SearchProvider(
    val id: String,
    val name: String,
)

@Serializable
internal data class SearchProviderFailure(
    val provider: SearchProvider,
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
