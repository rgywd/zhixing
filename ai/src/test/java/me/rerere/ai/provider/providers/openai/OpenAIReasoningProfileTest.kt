package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIReasoningProfileTest {
    @Test
    fun `standard profile preserves the six visible effort levels`() {
        val profile = OpenAIReasoningProfiles.resolve(
            host = "api.openai.com",
            modelId = "gpt-5.6-terra",
        )

        assertEquals("none", profile.effortFor(ReasoningLevel.OFF))
        assertEquals("low", profile.effortFor(ReasoningLevel.LOW))
        assertEquals("medium", profile.effortFor(ReasoningLevel.MEDIUM))
        assertEquals("high", profile.effortFor(ReasoningLevel.HIGH))
        assertEquals("xhigh", profile.effortFor(ReasoningLevel.XHIGH))
        assertEquals("max", profile.effortFor(ReasoningLevel.MAX))
    }

    @Test
    fun `DeepSeek family profile folds the shared slider into high and max`() {
        val profile = OpenAIReasoningProfiles.resolve(
            host = "api.deepseek.com",
            modelId = "deepseek-v4-pro",
        )

        assertEquals("none", profile.effortFor(ReasoningLevel.OFF))
        assertEquals("high", profile.effortFor(ReasoningLevel.LOW))
        assertEquals("high", profile.effortFor(ReasoningLevel.MEDIUM))
        assertEquals("high", profile.effortFor(ReasoningLevel.HIGH))
        assertEquals("max", profile.effortFor(ReasoningLevel.XHIGH))
        assertEquals("max", profile.effortFor(ReasoningLevel.MAX))
    }

    @Test
    fun `high max rules cover named families only on their known endpoints`() {
        assertEquals(
            OpenAIReasoningEffortScale.HIGH_MAX,
            OpenAIReasoningProfiles.resolve(
                host = "dashscope.aliyuncs.com",
                modelId = "qwen3.8-max-preview",
            ).effortScale,
        )
        assertEquals(
            OpenAIReasoningEffortScale.HIGH_MAX,
            OpenAIReasoningProfiles.resolve(
                host = "open.bigmodel.cn",
                modelId = "glm-4.7",
            ).effortScale,
        )
        assertEquals(
            OpenAIReasoningEffortScale.HIGH_MAX,
            OpenAIReasoningProfiles.resolve(
                host = "api.moonshot.cn",
                modelId = "kimi-k3",
            ).effortScale,
        )
        assertEquals(
            OpenAIReasoningEffortScale.STANDARD,
            OpenAIReasoningProfiles.resolve(
                host = "dashscope.aliyuncs.com",
                modelId = "custom-reasoner",
            ).effortScale,
        )
        assertEquals(
            OpenAIReasoningEffortScale.STANDARD,
            OpenAIReasoningProfiles.resolve(
                host = "gateway.example.com",
                modelId = "deepseek-v4-pro",
            ).effortScale,
        )
    }
}
