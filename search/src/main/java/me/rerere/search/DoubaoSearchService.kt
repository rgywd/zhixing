package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val DOUBAO_SEARCH_URL = "https://open.feedcoopapi.com/search_api/web_search"

object DoubaoSearchService : SearchService<SearchServiceOptions.DoubaoOptions> {
    override val name: String = "Doubao"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://console.volcengine.com/search-infinity/api-key")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.DoubaoOptions): InputSchema =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DoubaoOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DoubaoOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            require(serviceOptions.apiKey.isNotBlank()) { "Doubao API key is required" }

            val request = buildDoubaoSearchRequest(
                query = query,
                resultSize = commonOptions.resultSize,
                apiKey = serviceOptions.apiKey,
            )

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    error(
                        "Doubao search failed with code ${response.code}: " +
                            responseBody.take(500).ifBlank { response.message }
                    )
                }
                parseDoubaoSearchResponse(responseBody)
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DoubaoOptions
    ): Result<ScrapedResult> = Result.failure(Exception("Scraping is not supported for Doubao"))
}

internal fun buildDoubaoSearchRequest(
    query: String,
    resultSize: Int,
    apiKey: String,
    endpoint: String = DOUBAO_SEARCH_URL,
): Request {
    val body = buildJsonObject {
        put("Query", query)
        put("SearchType", "web")
        put("Count", resultSize)
        put("Filter", buildJsonObject {
            put("NeedUrl", true)
            put("NeedSummary", true)
            put("NeedContent", true)
        })
    }
    return Request.Builder()
        .url(endpoint)
        .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
        .addHeader("Authorization", "Bearer $apiKey")
        .build()
}

internal fun parseDoubaoSearchResponse(rawBody: String): SearchResult {
    val response = json.decodeFromString<DoubaoSearchResponse>(rawBody)
    val webResults = response.webResults ?: error("Doubao response does not contain WebResults")
    return SearchResult(
        items = webResults.mapNotNull { item ->
            val url = item.url.takeIf(String::isNotBlank) ?: return@mapNotNull null
            SearchResultItem(
                title = item.title.ifBlank { url },
                url = url,
                text = firstNotBlank(item.summary, item.snippet, item.content),
            )
        }
    )
}

private fun firstNotBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

@Serializable
internal data class DoubaoSearchResponse(
    @SerialName("WebResults")
    val webResults: List<DoubaoWebResult>? = null,
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
