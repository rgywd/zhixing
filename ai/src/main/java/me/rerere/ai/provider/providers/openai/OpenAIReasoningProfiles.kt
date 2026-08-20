package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.ReasoningLevel

/**
 * OpenAI-compatible providers expose a small number of effort scales even though model IDs change often.
 * Keep model-family matching in this table so request serializers do not accumulate per-model branches.
 */
internal enum class OpenAIReasoningEffortScale {
    STANDARD,
    HIGH_MAX,
}

internal data class OpenAIReasoningProfile(
    val effortScale: OpenAIReasoningEffortScale,
) {
    fun effortFor(level: ReasoningLevel): String {
        return when (effortScale) {
            OpenAIReasoningEffortScale.STANDARD -> when (level) {
                ReasoningLevel.AUTO -> error("AUTO has no explicit effort")
                else -> level.effort
            }
            OpenAIReasoningEffortScale.HIGH_MAX -> when (level) {
                ReasoningLevel.OFF -> "none"
                ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH -> "high"
                ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
                ReasoningLevel.AUTO -> error("AUTO has no explicit effort")
            }
        }
    }
}

internal object OpenAIReasoningProfiles {
    private data class Rule(
        val hosts: Set<String>,
        val modelPattern: Regex,
        val effortScale: OpenAIReasoningEffortScale,
    )

    private val rules = listOf(
        Rule(
            hosts = setOf(
                "api.deepseek.com",
                "dashscope.aliyuncs.com",
                "integrate.api.nvidia.com",
            ),
            modelPattern = Regex("deepseek", RegexOption.IGNORE_CASE),
            effortScale = OpenAIReasoningEffortScale.HIGH_MAX,
        ),
        Rule(
            hosts = setOf("open.bigmodel.cn", "dashscope.aliyuncs.com"),
            modelPattern = Regex("glm", RegexOption.IGNORE_CASE),
            effortScale = OpenAIReasoningEffortScale.HIGH_MAX,
        ),
        Rule(
            hosts = setOf("api.moonshot.cn", "dashscope.aliyuncs.com"),
            modelPattern = Regex("kimi|moonshot", RegexOption.IGNORE_CASE),
            effortScale = OpenAIReasoningEffortScale.HIGH_MAX,
        ),
        Rule(
            hosts = setOf("dashscope.aliyuncs.com"),
            modelPattern = Regex("qwen|qwq", RegexOption.IGNORE_CASE),
            effortScale = OpenAIReasoningEffortScale.HIGH_MAX,
        ),
    )

    fun resolve(host: String, modelId: String): OpenAIReasoningProfile {
        val scale = rules.firstOrNull { rule ->
            host.lowercase() in rule.hosts && rule.modelPattern.containsMatchIn(modelId)
        }?.effortScale ?: OpenAIReasoningEffortScale.STANDARD
        return OpenAIReasoningProfile(effortScale = scale)
    }
}
