package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTokenEstimatorTest {
    @Test
    fun `estimate includes stable system text and tool schemas`() {
        val messages = listOf(
            UIMessage.system("stable ".repeat(300)),
            UIMessage.user("hello"),
        )
        val tool = Tool(
            name = "lookup",
            description = "lookup description ".repeat(100),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("query", buildJsonObject { put("type", "string") })
                    }
                )
            },
            execute = { emptyList() },
        )

        val withoutTools = estimatePromptTokens(messages, emptyList())
        val withTools = estimatePromptTokens(messages, listOf(tool))

        assertTrue(withoutTools > estimatePromptTokens(messages.drop(1), emptyList()))
        assertTrue(withTools > withoutTools)
    }

    @Test
    fun `non text parts receive a conservative token reserve`() {
        val textOnly = listOf(UIMessage.user("hello"))
        val withImage = listOf(
            UIMessage(
                role = textOnly.single().role,
                parts = listOf(
                    UIMessagePart.Text("hello"),
                    UIMessagePart.Image("file:///image.png"),
                )
            )
        )

        assertTrue(estimatePromptTokens(withImage, emptyList()) > estimatePromptTokens(textOnly, emptyList()))
    }
}
