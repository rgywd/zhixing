package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

object AnySearchService : SearchService<SearchServiceOptions.AnySearchOptions> {
    override val name: String = "AnySearch"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://anysearch.com/console/api-keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.AnySearchOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.AnySearchOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AnySearchOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val body = buildJsonObject {
                put("query", query)
                put("limit", commonOptions.resultSize)
            }

            val requestBuilder = Request.Builder()
                .url("https://api.anysearch.com/v1/search")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Accept", "application/json")
                .addHeader("X-Anysearch-Client", "zhixing-rikkahub/1.0")

            if (serviceOptions.apiKey.isNotBlank()) {
                val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val response = httpClient.newCall(requestBuilder.build()).await()
            val responseBody = response.body.string()
            if (response.isSuccessful) {
                val searchResponse = json.decodeFromString<AnySearchResponse>(responseBody)
                if (searchResponse.code != 0) {
                    error("AnySearch search failed: ${searchResponse.message}")
                }

                SearchResult(
                    answer = null,
                    items = searchResponse.data.results.mapNotNull { result ->
                        val url = result.url ?: return@mapNotNull null
                        SearchResultItem(
                            title = result.title.orEmpty(),
                            url = url,
                            text = result.snippet ?: result.content.orEmpty()
                        )
                    }
                )
            } else {
                error("AnySearch search failed with code ${response.code}: $responseBody")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.AnySearchOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for AnySearch"))
    }

    @Serializable
    data class AnySearchResponse(
        val code: Int,
        val message: String? = null,
        val data: AnySearchData = AnySearchData(),
    )

    @Serializable
    data class AnySearchData(
        val results: List<AnySearchResultItem> = emptyList(),
    )

    @Serializable
    data class AnySearchResultItem(
        val title: String? = null,
        val url: String? = null,
        val snippet: String? = null,
        val content: String? = null,
    )
}
