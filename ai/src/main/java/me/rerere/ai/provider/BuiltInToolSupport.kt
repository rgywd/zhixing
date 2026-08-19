package me.rerere.ai.provider

import me.rerere.ai.registry.ModelRegistry
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Provider-owned capability table for model-native tools.
 *
 * Keep vendor and model allowlists here so UI callers only need to ask whether a tool is
 * supported. Add newly enabled Bailian models to [bailianHarnessModels].
 */
object BuiltInToolSupport {
    private val bailianHarnessModels = setOf(
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
        BuiltInTools.WebExtractor,
        BuiltInTools.WebSearchImage,
        BuiltInTools.ImageSearch -> supportsBailianHarnessTool(providerSetting, model, tool)
    }

    internal fun requiresResponsesApi(
        providerSetting: ProviderSetting.OpenAI,
        model: Model,
    ): Boolean = isBailianInternational(providerSetting) &&
        model.modelId.lowercase() in bailianHarnessModels &&
        model.tools.any { tool ->
            tool in bailianResponsesTools && supports(providerSetting, model, tool)
        }

    /**
     * Applies tool dependencies at both UI and request boundaries.
     * Web extraction is invalid without web search on Bailian Responses.
     */
    fun updateSelection(
        tools: Set<BuiltInTools>,
        tool: BuiltInTools,
        enabled: Boolean,
    ): Set<BuiltInTools> = when {
        enabled && tool == BuiltInTools.WebExtractor -> tools + BuiltInTools.Search + tool
        !enabled && tool == BuiltInTools.Search -> tools - BuiltInTools.Search - BuiltInTools.WebExtractor
        enabled -> tools + tool
        else -> tools - tool
    }

    internal fun normalizeForRequest(tools: Set<BuiltInTools>): Set<BuiltInTools> =
        if (BuiltInTools.WebExtractor in tools) tools + BuiltInTools.Search else tools

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
                    model.modelId.lowercase() in bailianHarnessModels)
        }

        else -> false
    }

    private fun supportsBailianHarnessTool(
        providerSetting: ProviderSetting?,
        model: Model,
        tool: BuiltInTools,
    ): Boolean = providerSetting is ProviderSetting.OpenAI &&
        isBailianInternational(providerSetting) &&
        model.modelId.lowercase() in bailianHarnessModels &&
        (tool != BuiltInTools.ImageSearch || Modality.IMAGE in model.inputModalities)

    private val bailianResponsesTools = setOf(
        BuiltInTools.Search,
        BuiltInTools.WebExtractor,
        BuiltInTools.WebSearchImage,
        BuiltInTools.ImageSearch,
    )

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
