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

    private fun model(modelId: String) = Model(modelId = modelId, displayName = modelId)
}
