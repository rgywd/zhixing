package me.rerere.ai.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.VOLCENGINE_AGENT_PLAN_BASE_URL
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient

/**
 * Volcengine Agent Plan uses a fixed OpenAI-compatible endpoint and a dedicated key.
 * It does not expose GET /models, so the picker is backed by a bundled catalog and
 * never performs model discovery.
 */
class VolcengineAgentPlanProvider(
    client: OkHttpClient,
    context: Context? = null,
) : Provider<ProviderSetting.VolcengineAgentPlan> {
    private val openAIProvider = OpenAIProvider(client = client, context = context)

    override suspend fun listModels(
        providerSetting: ProviderSetting.VolcengineAgentPlan
    ): List<Model> = SUPPORTED_MODELS

    override suspend fun generateText(
        providerSetting: ProviderSetting.VolcengineAgentPlan,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = openAIProvider.generateText(
        providerSetting = providerSetting.asOpenAISetting(),
        messages = messages,
        params = params,
    )

    override suspend fun streamText(
        providerSetting: ProviderSetting.VolcengineAgentPlan,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = openAIProvider.streamText(
        providerSetting = providerSetting.asOpenAISetting(),
        messages = messages,
        params = params,
    )

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.VolcengineAgentPlan,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult = openAIProvider.generateEmbedding(
        providerSetting = providerSetting.asOpenAISetting(),
        params = params,
    )

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        require(providerSetting is ProviderSetting.VolcengineAgentPlan) {
            "Expected Volcengine Agent Plan provider setting"
        }
        return openAIProvider.generateImage(
            providerSetting = providerSetting.asOpenAISetting(),
            params = params.asAgentPlanParams(),
        )
    }

    internal fun ImageGenerationParams.asAgentPlanParams(): ImageGenerationParams {
        val normalizedSize = size.takeUnless {
            it.isBlank() || it == ImageGenSize.AUTO.value
        } ?: DEFAULT_IMAGE_SIZE
        val outputFormat = CustomBody("output_format", JsonPrimitive("png"))
        return copy(
            size = normalizedSize,
            customBody = listOf(outputFormat) + customBody,
        )
    }

    internal fun ProviderSetting.VolcengineAgentPlan.asOpenAISetting() = ProviderSetting.OpenAI(
        id = id,
        enabled = enabled,
        name = name,
        models = models,
        apiKey = apiKey,
        baseUrl = VOLCENGINE_AGENT_PLAN_BASE_URL,
        useResponseApi = true,
        includeHistoryReasoning = includeHistoryReasoning,
    )

    companion object {
        val SUPPORTED_TEXT_MODELS = listOf(
            "ark-code-latest",
            "doubao-seed-2.0-mini",
            "doubao-seed-2.0-lite",
            "deepseek-v4-flash",
            "doubao-seed-evolving",
            "doubao-seed-2.0-code",
            "doubao-seed-2.0-pro",
            "minimax-m2.7",
            "minimax-m3",
            "glm-5.2",
            "glm-latest",
            "kimi-k2.6",
            "kimi-k2.7-code",
            "deepseek-v4-pro",
        ).map { modelId ->
            Model(modelId = modelId, displayName = modelId)
        }

        val SUPPORTED_EMBEDDING_MODELS = listOf(
            Model(
                modelId = "doubao-embedding-vision",
                displayName = "Doubao Embedding Vision",
                type = ModelType.EMBEDDING,
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            )
        )

        val SUPPORTED_IMAGE_MODELS = listOf(
            Model(
                modelId = "doubao-seedream-5.0-lite",
                displayName = "Doubao Seedream 5.0 Lite",
                type = ModelType.IMAGE,
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.IMAGE),
            )
        )

        val SUPPORTED_MODELS = SUPPORTED_TEXT_MODELS +
            SUPPORTED_EMBEDDING_MODELS +
            SUPPORTED_IMAGE_MODELS

        private const val DEFAULT_IMAGE_SIZE = "2K"
    }
}
