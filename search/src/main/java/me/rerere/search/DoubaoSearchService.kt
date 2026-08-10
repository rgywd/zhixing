package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val DOUBAO_SEARCH_URL = "https://open.feedcoopapi.com/search_api/web_search"
private const val DOUBAO_MAX_QPS = 5
private const val ONE_SECOND_MILLIS = 1_000L

object DoubaoSearchService : SearchService<SearchServiceOptions.DoubaoOptions> {
    override val name: String = "Doubao"
    private val rateLimiter = DoubaoRateLimiter()

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(onClick = { urlHandler.openUri("https://console.volcengine.com/search-infinity/api-key") }) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.DoubaoOptions): InputSchema = querySchema()

    override fun imageParameters(options: SearchServiceOptions.DoubaoOptions): InputSchema = querySchema()

    override fun scrapingParameters(options: SearchServiceOptions.DoubaoOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DoubaoOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val responseBody = executeSearch(
                query = params["query"]?.jsonPrimitive?.content ?: error("query is required"),
                resultSize = commonOptions.resultSize,
                apiKey = serviceOptions.apiKey,
                searchType = DoubaoSearchType.WEB,
                timeoutMillis = commonOptions.searchTimeoutMillis(),
            )
            parseDoubaoSearchResponse(responseBody)
        }
    }

    override suspend fun searchImages(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DoubaoOptions
    ): Result<ImageSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val responseBody = executeSearch(
                query = params["query"]?.jsonPrimitive?.content ?: error("query is required"),
                resultSize = commonOptions.resultSize.coerceAtMost(5),
                apiKey = serviceOptions.apiKey,
                searchType = DoubaoSearchType.IMAGE,
                timeoutMillis = commonOptions.searchTimeoutMillis(),
            )
            parseDoubaoImageSearchResponse(responseBody)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DoubaoOptions
    ): Result<ScrapedResult> = Result.failure(UnsupportedOperationException("Scraping is not supported for Doubao"))

    private suspend fun executeSearch(
        query: String,
        resultSize: Int,
        apiKey: String,
        searchType: DoubaoSearchType,
        timeoutMillis: Long,
    ): String {
        require(apiKey.isNotBlank()) { "Doubao API key is required" }
        rateLimiter.acquire()
        val request = buildDoubaoSearchRequest(query, resultSize, apiKey, searchType)
        return httpClient.newCall(request, timeoutMillis).await().use { response ->
            if (!response.isSuccessful) {
                throw SearchProviderException("HTTP_${response.code}")
            }
            response.body.string()
        }
    }
}

internal enum class DoubaoSearchType(val wireValue: String) {
    WEB("web"),
    IMAGE("image"),
}

internal fun buildDoubaoSearchRequest(
    query: String,
    resultSize: Int,
    apiKey: String,
    searchType: DoubaoSearchType = DoubaoSearchType.WEB,
    endpoint: String = DOUBAO_SEARCH_URL,
): Request {
    val body = buildJsonObject {
        put("Query", query)
        put("SearchType", searchType.wireValue)
        put("Count", resultSize.coerceIn(1, 50))
        if (searchType == DoubaoSearchType.WEB) {
            put("Filter", buildJsonObject {
                put("NeedUrl", true)
                put("NeedSummary", true)
                put("NeedContent", true)
            })
        }
    }
    return Request.Builder()
        .url(endpoint)
        .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
        .addHeader("Authorization", "Bearer $apiKey")
        .build()
}

internal fun parseDoubaoSearchResponse(rawBody: String): SearchResult {
    val response = parseDoubaoResponse(rawBody)
    val webResults = response.result?.webResults
        ?: response.webResults
        ?: error("Doubao response does not contain Result.WebResults or WebResults")
    return SearchResult(
        items = webResults.mapNotNull { item ->
            val url = item.url.takeIf(String::isNotBlank) ?: return@mapNotNull null
            SearchResultItem(
                title = item.title.ifBlank { url },
                url = url,
                text = firstNotBlank(item.summary, item.content, item.snippet),
            )
        },
        requestId = response.responseMetadata?.requestId,
    )
}

internal fun parseDoubaoImageSearchResponse(rawBody: String): ImageSearchResult {
    val response = parseDoubaoResponse(rawBody)
    val imageResults = response.result?.imageResults
        ?: response.imageResults
        ?: error("Doubao response does not contain Result.ImageResults or ImageResults")
    return ImageSearchResult(
        items = imageResults.mapNotNull { item ->
            val imageUrl = item.image?.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ImageSearchItem(
                imageUrl = imageUrl,
                sourceUrl = item.url?.takeIf(String::isNotBlank),
                title = item.title?.takeIf(String::isNotBlank),
                siteName = item.siteName?.takeIf(String::isNotBlank),
                width = item.image.width,
                height = item.image.height,
                shape = item.image.shape,
                rankScore = item.rankScore,
                watermark = runCatching { item.watermark?.jsonPrimitive?.booleanOrNull }.getOrNull(),
                blurDescription = item.blurDescription,
            )
        },
        requestId = response.responseMetadata?.requestId,
    )
}

private fun parseDoubaoResponse(rawBody: String): DoubaoSearchResponse {
    val response = json.decodeFromString<DoubaoSearchResponse>(rawBody)
    response.responseMetadata?.error?.let { error ->
        val code = runCatching { error.code?.jsonPrimitive?.content }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: "UPSTREAM_ERROR"
        throw SearchProviderException(code, response.responseMetadata.requestId)
    }
    return response
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

private fun firstNotBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

@Serializable
internal data class DoubaoSearchResponse(
    @SerialName("ResponseMetadata")
    val responseMetadata: DoubaoResponseMetadata? = null,
    @SerialName("Result")
    val result: DoubaoSearchResult? = null,
    @SerialName("WebResults")
    val webResults: List<DoubaoWebResult>? = null,
    @SerialName("ImageResults")
    val imageResults: List<DoubaoImageResult>? = null,
)

@Serializable
internal data class DoubaoResponseMetadata(
    @SerialName("RequestId")
    val requestId: String? = null,
    @SerialName("Error")
    val error: DoubaoResponseError? = null,
)

@Serializable
internal data class DoubaoResponseError(
    @SerialName("Code")
    val code: JsonElement? = null,
    @SerialName("Message")
    val message: String? = null,
)

@Serializable
internal data class DoubaoSearchResult(
    @SerialName("WebResults")
    val webResults: List<DoubaoWebResult>? = null,
    @SerialName("ImageResults")
    val imageResults: List<DoubaoImageResult>? = null,
)

@Serializable
internal data class DoubaoWebResult(
    @SerialName("Title")
    val title: String = "",
    @SerialName("Url")
    val url: String = "",
    @SerialName("Summary")
    val summary: String? = null,
    @SerialName("Snippet")
    val snippet: String? = null,
    @SerialName("Content")
    val content: String? = null,
)

@Serializable
internal data class DoubaoImageResult(
    @SerialName("Title")
    val title: String? = null,
    @SerialName("SiteName")
    val siteName: String? = null,
    @SerialName("Url")
    val url: String? = null,
    @SerialName("Image")
    val image: DoubaoImage? = null,
    @SerialName("RankScore")
    val rankScore: Double? = null,
    @SerialName("Watermark")
    val watermark: JsonElement? = null,
    @SerialName("BlurDes")
    val blurDescription: String? = null,
)

@Serializable
internal data class DoubaoImage(
    @SerialName("Url")
    val url: String = "",
    @SerialName("Width")
    val width: Int? = null,
    @SerialName("Height")
    val height: Int? = null,
    @SerialName("Shape")
    val shape: String? = null,
)

private class DoubaoRateLimiter {
    private val mutex = Mutex()
    private val requestTimes = ArrayDeque<Long>()

    suspend fun acquire() = mutex.withLock {
        while (true) {
            val now = System.currentTimeMillis()
            while (requestTimes.firstOrNull()?.let { now - it >= ONE_SECOND_MILLIS } == true) {
                requestTimes.removeFirst()
            }
            if (requestTimes.size < DOUBAO_MAX_QPS) {
                requestTimes.addLast(now)
                return@withLock
            }
            delay((ONE_SECOND_MILLIS - (now - requestTimes.first())).coerceAtLeast(1L))
        }
    }
}
