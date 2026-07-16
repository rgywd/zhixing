package me.rerere.ai.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.VOLCENGINE_AGENT_PLAN_BASE_URL
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient

/**
 * Volcengine Agent Plan's OpenAI-compatible endpoint only exposes the Responses API.
 * Its model catalog is configured by name and does not expose GET /models, so the
 * picker is backed by a bundled catalog and never performs model discovery.
 */
class VolcengineAgentPlanProvider(
    client: OkHttpClient,
    context: Context? = null,
) : Provider<ProviderSetting.VolcengineAgentPlan> {
    private val openAIProvider = OpenAIProvider(client = client, context = context)

    override suspend fun listModels(
        providerSetting: ProviderSetting.VolcengineAgentPlan
    ): List<Model> = SUPPORTED_TEXT_MODELS

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
    }
}
