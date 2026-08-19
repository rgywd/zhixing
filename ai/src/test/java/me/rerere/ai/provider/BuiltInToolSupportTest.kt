package me.rerere.ai.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInToolSupportTest {
    private val supportedModelIds = listOf(
        "qwen3.8-max",
        "qwen3.7-plus",
        "deepseek-v4-flash-0731",
    )

    @Test
    fun `international Bailian supports web search for configured model list`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        )

        supportedModelIds.forEach { modelId ->
            assertTrue(
                modelId,
                BuiltInToolSupport.supports(provider, model(modelId), BuiltInTools.Search)
            )
        }
    }

    @Test
    fun `international Bailian exposes selected Harness tools for configured model list`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        )
        supportedModelIds.forEach { modelId ->
            assertTrue(
                modelId,
                BuiltInToolSupport.supports(provider, model(modelId), BuiltInTools.WebExtractor)
            )
        }

        listOf("qwen3.8-max", "qwen3.7-plus").forEach { modelId ->
            val imageModel = model(modelId).copy(
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE)
            )
            assertTrue(BuiltInToolSupport.supports(provider, imageModel, BuiltInTools.WebSearchImage))
            assertTrue(BuiltInToolSupport.supports(provider, imageModel, BuiltInTools.ImageSearch))
        }
        assertFalse(
            BuiltInToolSupport.supports(
                provider,
                model("deepseek-v4-flash-0731"),
                BuiltInTools.WebSearchImage,
            )
        )
        assertFalse(
            BuiltInToolSupport.supports(
                provider,
                model("deepseek-v4-flash-0731"),
                BuiltInTools.ImageSearch,
            )
        )
    }

    @Test
    fun `international workspace endpoint is recognized without matching China endpoint`() {
        val international = ProviderSetting.OpenAI(
            baseUrl = "https://workspace.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"
        )
        val china = ProviderSetting.OpenAI(
            baseUrl = "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
        )

        assertTrue(BuiltInToolSupport.isBailianInternational(international))
        assertFalse(BuiltInToolSupport.isBailianInternational(china))
    }

    @Test
    fun `preview and unconfigured model ids stay out of Bailian search allowlist`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        )

        assertFalse(
            BuiltInToolSupport.supports(provider, model("qwen3.8-max-preview"), BuiltInTools.Search)
        )
        assertFalse(
            BuiltInToolSupport.supports(provider, model("qwen3.7-max"), BuiltInTools.Search)
        )
    }

    @Test
    fun `Bailian search selects Responses API only after tool is enabled`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        )

        assertFalse(BuiltInToolSupport.requiresResponsesApi(provider, model("qwen3.8-max")))
        assertTrue(
            BuiltInToolSupport.requiresResponsesApi(
                provider,
                model("qwen3.8-max").copy(tools = setOf(BuiltInTools.Search))
            )
        )
    }

    @Test
    fun `Bailian Harness tools select Responses API without web search toggle`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        )

        assertTrue(
            BuiltInToolSupport.requiresResponsesApi(
                provider,
                model("qwen3.7-plus").copy(tools = setOf(BuiltInTools.WebSearchImage))
            )
        )
        assertFalse(
            BuiltInToolSupport.requiresResponsesApi(
                provider,
                model("deepseek-v4-flash-0731").copy(tools = setOf(BuiltInTools.ImageSearch))
            )
        )
        assertFalse(
            BuiltInToolSupport.requiresResponsesApi(
                provider,
                model("deepseek-v4-flash-0731").copy(tools = setOf(BuiltInTools.WebSearchImage))
            )
        )
    }

    @Test
    fun `web extractor selection keeps web search dependency consistent`() {
        val extractorEnabled = BuiltInToolSupport.updateSelection(
            emptySet(),
            BuiltInTools.WebExtractor,
            enabled = true,
        )

        assertTrue(BuiltInTools.WebExtractor in extractorEnabled)
        assertTrue(BuiltInTools.Search in extractorEnabled)

        val searchDisabled = BuiltInToolSupport.updateSelection(
            extractorEnabled,
            BuiltInTools.Search,
            enabled = false,
        )
        assertFalse(BuiltInTools.Search in searchDisabled)
        assertFalse(BuiltInTools.WebExtractor in searchDisabled)
    }

    private fun model(modelId: String) = Model(modelId = modelId, displayName = modelId)
}
