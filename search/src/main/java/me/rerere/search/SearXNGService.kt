package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

internal enum class SearXNGCategory {
    WEB,
    IMAGES,
}

object SearXNGService : SearchService<SearchServiceOptions.SearXNGOptions> {
    override val name: String = "SearXNG"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.searxng_desc_1))
        Text(stringResource(R.string.searxng_desc_2))
    }

    override fun parameters(options: SearchServiceOptions.SearXNGOptions): InputSchema = querySchema()

    override fun imageParameters(options: SearchServiceOptions.SearXNGOptions): InputSchema = querySchema()

    override fun scrapingParameters(options: SearchServiceOptions.SearXNGOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions,
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            executeSearch(
                request = buildSearXNGRequest(query, serviceOptions, SearXNGCategory.WEB),
                timeoutMillis = commonOptions.searchTimeoutMillis(),
            ).toSearchResult(commonOptions.resultSize)
        }
    }

    override suspend fun searchImages(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions,
    ): Result<ImageSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            executeSearch(
                request = buildSearXNGRequest(query, serviceOptions, SearXNGCategory.IMAGES),
                timeoutMillis = commonOptions.searchTimeoutMillis(),
            ).toImageSearchResult(commonOptions.resultSize)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions,
    ): Result<ScrapedResult> = Result.failure(
        UnsupportedOperationException("Scraping is not supported for SearXNG")
    )

    private suspend fun executeSearch(request: Request, timeoutMillis: Long): SearXNGResponse {
        val responseBody = httpClient.newCall(request, timeoutMillis).await().use { response ->
            if (!response.isSuccessful) {
                throw SearchProviderException("HTTP_${response.code}")
            }
            response.body.string()
        }
        return runCatching { parseSearXNGResponse(responseBody) }
            .getOrElse { throw SearchProviderException("INVALID_RESPONSE") }
    }
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

internal fun buildSearXNGRequest(
    query: String,
    options: SearchServiceOptions.SearXNGOptions,
    category: SearXNGCategory,
): Request {
    require(options.url.isNotBlank()) { "SearXNG URL cannot be empty" }
    val url = "${options.url.trim().trimEnd('/')}/search"
        .toHttpUrl()
        .newBuilder()
        .addQueryParameter("q", query)
        .addQueryParameter("format", "json")
        .apply {
            when (category) {
                SearXNGCategory.WEB -> options.engines
                    .takeIf(String::isNotBlank)
                    ?.let { addQueryParameter("engines", it) }
                SearXNGCategory.IMAGES -> addQueryParameter("categories", "images")
            }
            options.language
                .takeIf(String::isNotBlank)
                ?.let { addQueryParameter("language", it) }
        }
        .build()

    return Request.Builder()
        .url(url)
        .get()
        .apply {
            if (options.username.isNotBlank() && options.password.isNotBlank()) {
                header("Authorization", Credentials.basic(options.username, options.password))
            }
        }
        .build()
}

internal fun parseSearXNGResponse(rawBody: String): SearXNGResponse =
    json.decodeFromString<SearXNGResponse>(rawBody)

internal fun SearXNGResponse.toSearchResult(resultSize: Int): SearchResult = SearchResult(
    items = results.mapNotNull { result ->
        val url = result.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        SearchResultItem(
            title = result.title?.takeIf(String::isNotBlank) ?: url,
            url = url,
            text = result.content.orEmpty(),
        )
    }.take(resultSize.coerceAtLeast(0)),
)

internal fun SearXNGResponse.toImageSearchResult(resultSize: Int): ImageSearchResult = ImageSearchResult(
    items = results.mapNotNull { result ->
        val imageUrl = result.imageUrl() ?: return@mapNotNull null
        val parsedResolution = parseResolution(result.resolution)
        ImageSearchItem(
            imageUrl = imageUrl,
            sourceUrl = result.url?.takeIf(String::isNotBlank),
            title = result.title?.takeIf(String::isNotBlank),
            siteName = result.source?.takeIf(String::isNotBlank),
            width = result.width ?: parsedResolution?.first,
            height = result.height ?: parsedResolution?.second,
        )
    }.take(resultSize.coerceAtLeast(0)),
)

private fun SearXNGResult.imageUrl(): String? =
    imgSrc?.takeIf(String::isNotBlank) ?: thumbnailSrc?.takeIf(String::isNotBlank)

private fun parseResolution(resolution: String?): Pair<Int, Int>? {
    val match = resolution
        ?.let { RESOLUTION_PATTERN.matchEntire(it) }
        ?: return null
    return match.groupValues[1].toIntOrNull()?.let { width ->
        match.groupValues[2].toIntOrNull()?.let { height -> width to height }
    }
}

private val RESOLUTION_PATTERN = Regex("""\s*(\d+)\s*[x×]\s*(\d+)\s*""", RegexOption.IGNORE_CASE)

@Serializable
internal data class SearXNGResponse(
    val results: List<SearXNGResult> = emptyList(),
)

@Serializable
internal data class SearXNGResult(
    val url: String? = null,
    val title: String? = null,
    val content: String? = null,
    @SerialName("img_src")
    val imgSrc: String? = null,
    @SerialName("thumbnail_src")
    val thumbnailSrc: String? = null,
    val source: String? = null,
    val resolution: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val engine: String? = null,
)
