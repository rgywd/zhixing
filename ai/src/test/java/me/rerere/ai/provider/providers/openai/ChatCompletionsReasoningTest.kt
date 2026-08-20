package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsReasoningTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

    @Test
    fun `standard OpenAI-compatible endpoint preserves max and off`() {
        assertEquals(
            "max",
            request("https://api.openai.com/v1", "gpt-5.6-terra", ReasoningLevel.MAX)
                .getValue("reasoning_effort").jsonPrimitive.content,
        )
        assertEquals(
            "none",
            request("https://api.openai.com/v1", "gpt-5.6-terra", ReasoningLevel.OFF)
                .getValue("reasoning_effort").jsonPrimitive.content,
        )
    }

    @Test
    fun `DeepSeek family folds visible levels to supported high and max values`() {
        assertEquals(
            "high",
            request("https://api.deepseek.com/v1", "deepseek-v4-pro", ReasoningLevel.MEDIUM)
                .getValue("reasoning_effort").jsonPrimitive.content,
        )
        val maxRequest = request(
            "https://api.deepseek.com/v1",
            "deepseek-ai/DeepSeek-V4-Flash",
            ReasoningLevel.XHIGH,
        )
        assertEquals("max", maxRequest.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("enabled", maxRequest.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `DashScope folds Qwen and keeps budget mapping for unknown model families`() {
        val qwen = request(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen3.8-max-preview",
            ReasoningLevel.MEDIUM,
        )
        assertTrue(qwen.getValue("enable_thinking").jsonPrimitive.boolean)
        assertEquals("high", qwen.getValue("reasoning_effort").jsonPrimitive.content)
        assertFalse(qwen.containsKey("thinking_budget"))

        val unknown = request(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "custom-reasoner",
            ReasoningLevel.MEDIUM,
        )
        assertTrue(unknown.getValue("enable_thinking").jsonPrimitive.boolean)
        assertEquals(2000, unknown.getValue("thinking_budget").jsonPrimitive.content.toInt())
        assertFalse(unknown.containsKey("reasoning_effort"))

        val deepSeek = request(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "deepseek-ai/DeepSeek-V4-Pro",
            ReasoningLevel.MAX,
        )
        assertTrue(deepSeek.getValue("enable_thinking").jsonPrimitive.boolean)
        assertEquals("max", deepSeek.getValue("reasoning_effort").jsonPrimitive.content)
        assertFalse(deepSeek.containsKey("thinking_budget"))
    }

    @Test
    fun `GLM family uses high max scale only on its known endpoint`() {
        val request = request(
            "https://open.bigmodel.cn/api/paas/v4",
            "glm-4.7",
            ReasoningLevel.XHIGH,
        )
        assertEquals("max", request.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("enabled", request.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `Kimi family uses high max scale on Moonshot endpoint`() {
        val request = request(
            "https://api.moonshot.cn/v1",
            "kimi-k3",
            ReasoningLevel.LOW,
        )
        assertEquals("high", request.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("enabled", request.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `non Chat auto keeps provider automatic behavior without explicit effort`() {
        val openAi = request(
            "https://api.openai.com/v1",
            "gpt-5.6-terra",
            ReasoningLevel.AUTO,
        )
        assertFalse(openAi.containsKey("reasoning_effort"))

        val qwen = request(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen3.8-max-preview",
            ReasoningLevel.AUTO,
        )
        assertTrue(qwen.getValue("enable_thinking").jsonPrimitive.boolean)
        assertFalse(qwen.containsKey("reasoning_effort"))
        assertFalse(qwen.containsKey("thinking_budget"))
    }

    private fun request(
        baseUrl: String,
        modelId: String,
        level: ReasoningLevel,
    ) = api.buildChatCompletionRequest(
        messages = listOf(UIMessage.user("hello")),
        params = TextGenerationParams(
            model = Model(
                modelId = modelId,
                displayName = modelId,
                abilities = listOf(ModelAbility.REASONING),
            ),
            reasoningLevel = level,
        ),
        providerSetting = ProviderSetting.OpenAI(baseUrl = baseUrl),
    )
}
