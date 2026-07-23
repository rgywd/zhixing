package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart

private val RESEARCH_TOOL_NAMES = setOf("search_web", "scrape_web")

/**
 * 思考步骤类型，用于分组 Reasoning 和 Tool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    data class ResearchPurposeStep(
        val purpose: String,
        val tools: List<UIMessagePart.Tool>,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning 和 Tool 会被分组到一个 ThinkingBlock 中
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(
                MessagePartBlock.ThinkingBlock(
                    currentThinkingSteps.toList().groupResearchToolsByPurpose()
                )
            )
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                currentThinkingSteps.add(ThinkingStep.ToolStep(part))
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}

private fun List<ThinkingStep>.groupResearchToolsByPurpose(): List<ThinkingStep> {
    val result = mutableListOf<ThinkingStep>()
    var currentPurpose: String? = null
    var currentTools = mutableListOf<UIMessagePart.Tool>()

    fun flushResearchTools() {
        val purpose = currentPurpose
        if (purpose != null && currentTools.isNotEmpty()) {
            result += ThinkingStep.ResearchPurposeStep(
                purpose = purpose,
                tools = currentTools.toList(),
            )
        }
        currentPurpose = null
        currentTools = mutableListOf()
    }

    forEach { step ->
        val tool = (step as? ThinkingStep.ToolStep)?.tool
        val purpose = tool?.researchPurpose()
        if (tool != null && purpose != null) {
            if (currentPurpose != purpose) {
                flushResearchTools()
                currentPurpose = purpose
            }
            currentTools += tool
        } else {
            flushResearchTools()
            result += step
        }
    }
    flushResearchTools()
    return result
}

private fun UIMessagePart.Tool.researchPurpose(): String? {
    if (toolName !in RESEARCH_TOOL_NAMES) return null
    return runCatching {
        inputAsJson().jsonObject["purpose"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }.getOrNull()
}
