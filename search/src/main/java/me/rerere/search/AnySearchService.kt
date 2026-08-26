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
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val ANYSEARCH_SEARCH_URL = "https://api.anysearch.com/v1/search"
internal const val ANYSEARCH_MCP_URL = "https://api.anysearch.com/mcp"
object AnySearchService : SearchService<SearchServiceOptions.AnySearchOptions> {
    override val name: String = "AnySearch"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(onClick = { urlHandler.openUri("https://anysearch.com/console/api-keys") }) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.AnySearchOptions): InputSchema = querySchema()

    override fun scrapingParameters(options: SearchServiceOptions.AnySearchOptions): InputSchema =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "URL to extract as Markdown")
                })
            },
            required = listOf("url"),
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AnySearchOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val response = executeSearch(
                request = buildAnySearchRequest(
                    query = query,
                    resultSize = commonOptions.resultSize,
                    apiKey = resolveApiKey(serviceOptions),
                ),
                timeoutMillis = commonOptions.searchTimeoutMillis(),
            )
            SearchResult(
                items = response.data.results.mapNotNull { result ->
                    val url = result.url?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    SearchResultItem(
                        title = result.title?.takeIf(String::isNotBlank) ?: url,
                        url = url,
                        text = firstNotBlank(result.content, result.snippet),
                    )
                },
                requestId = response.request_id,
            )
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AnySearchOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val request = buildAnySearchExtractRequest(url, resolveApiKey(serviceOptions))
            val responseBody = httpClient.newCall(request, commonOptions.scrapeTimeoutMillis()).await().use { response ->
                if (!response.isSuccessful) {
                    throw SearchProviderException("HTTP_${response.code}")
                }
                response.body.string()
            }
            val content = parseAnySearchExtractResponse(responseBody)
            ScrapedResult(
                urls = listOf(ScrapedResultUrl(url = url, content = content)),
                requestId = parseAnySearchExtractRequestId(responseBody),
            )
        }
    }

    private suspend fun executeSearch(request: Request, timeoutMillis: Long): AnySearchResponse {
        val responseBody = httpClient.newCall(request, timeoutMillis).await().use { response ->
            if (!response.isSuccessful) {
                throw SearchProviderException("HTTP_${response.code}")
            }
            response.body.string()
        }
        return parseAnySearchResponse(responseBody)
    }

    private fun resolveApiKey(options: SearchServiceOptions.AnySearchOptions): String? =
        options.apiKey.takeIf(String::isNotBlank)?.let { keyRoulette.next(it, options.id.toString()) }
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

internal fun buildAnySearchRequest(
    query: String,
    resultSize: Int,
    apiKey: String?,
    endpoint: String = ANYSEARCH_SEARCH_URL,
): Request {
    val body = buildJsonObject {
        put("query", query)
        put("max_results", resultSize.coerceIn(1, 20))
    }
    return Request.Builder()
        .url(endpoint)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .addHeader("Accept", "application/json")
        .addHeader("X-Anysearch-Client", "zhixing-rikkahub/1.0")
        .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
        .build()
}

internal fun buildAnySearchExtractRequest(
    url: String,
    apiKey: String?,
    endpoint: String = ANYSEARCH_MCP_URL,
): Request {
    val body = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "tools/call")
        put("params", buildJsonObject {
            put("name", "extract")
            put("arguments", buildJsonObject { put("url", url) })
        })
    }
    return Request.Builder()
        .url(endpoint)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .addHeader("Accept", "application/json")
        .addHeader("X-Anysearch-Client", "zhixing-rikkahub/1.0")
        .apply { apiKey?.let { addHeader("Authorization", "Bearer $it") } }
        .build()
}

internal fun parseAnySearchResponse(rawBody: String): AnySearchResponse {
    val response = json.decodeFromString<AnySearchResponse>(rawBody)
    if (response.code != 0) {
        throw SearchProviderException(response.code.toString(), response.request_id)
    }
    return response
}

internal fun parseAnySearchExtractResponse(rawBody: String): String {
    val response = json.decodeFromString<AnySearchMcpResponse>(rawBody)
    response.error?.let { error ->
        throw SearchProviderException(error.code?.toString() ?: "MCP_ERROR")
    }
    return response.result?.content
        ?.firstOrNull { it.type == "text" }
        ?.text
        ?.takeIf(String::isNotBlank)
        ?: error("AnySearch extract returned no text content")
}

internal fun parseAnySearchExtractRequestId(rawBody: String): String? =
    json.decodeFromString<AnySearchMcpResponse>(rawBody).result?.meta?.requestId

@Serializable
internal data class AnySearchResponse(
    val code: Int,
    val message: String? = null,
    val request_id: String? = null,
    val data: AnySearchData = AnySearchData(),
)

@Serializable
internal data class AnySearchData(
    val results: List<AnySearchResultItem> = emptyList(),
    val metadata: AnySearchMetadata? = null,
)

@Serializable
internal data class AnySearchMetadata(
    val total_results: Int? = null,
    val search_time_ms: Long? = null,
)

@Serializable
internal data class AnySearchResultItem(
    val title: String? = null,
    val url: String? = null,
    val snippet: String? = null,
    val content: String? = null,
)

@Serializable
internal data class AnySearchMcpResponse(
    val result: AnySearchMcpResult? = null,
    val error: AnySearchMcpError? = null,
)

@Serializable
internal data class AnySearchMcpMeta(
    @SerialName("request_id")
    val requestId: String? = null,
)

@Serializable
internal data class AnySearchMcpResult(
    val content: List<AnySearchMcpContent> = emptyList(),
    @SerialName("_meta")
    val meta: AnySearchMcpMeta? = null,
)

@Serializable
internal data class AnySearchMcpContent(
    val type: String,
    val text: String? = null,
)

@Serializable
internal data class AnySearchMcpError(
    val code: Int? = null,
    val message: String? = null,
)

private fun firstNotBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
