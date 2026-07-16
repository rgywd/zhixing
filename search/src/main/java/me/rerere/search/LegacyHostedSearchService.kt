package me.rerere.search

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema

/**
 * Decoder-compatible placeholder for configurations created before Zhixing
 * removed its dependency on the legacy hosted search backend.
 */
object LegacyHostedSearchService : SearchService<SearchServiceOptions.LegacyHostedOptions> {
    override val name: String = "Legacy hosted search (disabled)"

    @Composable
    override fun Description() = Unit

    override fun parameters(options: SearchServiceOptions.LegacyHostedOptions): InputSchema =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query"),
        )

    override fun scrapingParameters(options: SearchServiceOptions.LegacyHostedOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.LegacyHostedOptions,
    ): Result<SearchResult> = Result.failure(
        UnsupportedOperationException("This legacy hosted search service is no longer available. Choose another search provider."),
    )

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.LegacyHostedOptions,
    ): Result<ScrapedResult> = Result.failure(
        UnsupportedOperationException("This legacy hosted search service is no longer available. Choose another search provider."),
    )
}
