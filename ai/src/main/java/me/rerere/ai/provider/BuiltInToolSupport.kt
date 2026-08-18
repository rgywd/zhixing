package me.rerere.ai.provider

import me.rerere.ai.registry.ModelRegistry
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Provider-owned capability table for model-native tools.
 *
 * Keep vendor and model allowlists here so UI callers only need to ask whether a tool is
 * supported. Add newly enabled Bailian models to [bailianWebSearchModels].
 */
object BuiltInToolSupport {
    private val bailianWebSearchModels = setOf(
        "qwen3.8-max",
        "qwen3.7-plus",
        "deepseek-v4-flash-0731",
    )

    fun supports(
        providerSetting: ProviderSetting?,
        model: Model,
        tool: BuiltInTools,
    ): Boolean = when (tool) {
        BuiltInTools.Search -> supportsSearch(providerSetting, model)
        BuiltInTools.UrlContext,
        BuiltInTools.ImageGeneration -> false
    }

    internal fun requiresResponsesApi(
        providerSetting: ProviderSetting.OpenAI,
        model: Model,
    ): Boolean = model.tools.contains(BuiltInTools.Search) &&
        isBailianInternational(providerSetting) &&
        model.modelId.lowercase() in bailianWebSearchModels

    internal fun isBailianInternational(providerSetting: ProviderSetting.OpenAI): Boolean {
        val host = providerSetting.baseUrl.toHttpUrlOrNull()?.host ?: return false
        return isBailianInternationalHost(host)
    }

    internal fun isBailianInternationalHost(host: String): Boolean {
        return host in bailianInternationalSharedHosts ||
            bailianInternationalWorkspaceRegions.any { region ->
                host.endsWith(".$region.maas.aliyuncs.com")
            }
    }

    private fun supportsSearch(providerSetting: ProviderSetting?, model: Model): Boolean = when (providerSetting) {
        is ProviderSetting.Google -> ModelRegistry.GEMINI_SERIES.match(model.modelId)
        is ProviderSetting.OpenAI -> {
            model.modelId.contains("gpt-", ignoreCase = true) ||
                (isBailianInternational(providerSetting) &&
                    model.modelId.lowercase() in bailianWebSearchModels)
        }

        else -> false
    }

    private val bailianInternationalSharedHosts = setOf(
        "dashscope-intl.aliyuncs.com",
        "dashscope-us.aliyuncs.com",
        "cn-hongkong.dashscope.aliyuncs.com",
    )

    private val bailianInternationalWorkspaceRegions = setOf(
        "ap-southeast-1",
        "ap-northeast-1",
        "us-east-1",
        "eu-central-1",
        "cn-hongkong",
    )
}
