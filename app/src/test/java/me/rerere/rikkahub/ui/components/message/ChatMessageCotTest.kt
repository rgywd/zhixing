package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageCotTest {
    @Test
    fun `adjacent research tools with the same purpose form one subtree`() {
        val parts = listOf(
            researchTool("search-1", "search_web", "Confirm the campus"),
            researchTool("scrape-1", "scrape_web", "Confirm the campus"),
        )

        val steps = parts.singleThinkingBlock().steps
        val group = steps.single() as ThinkingStep.ResearchPurposeStep

        assertEquals("Confirm the campus", group.purpose)
        assertEquals(listOf("search-1", "scrape-1"), group.tools.map { it.toolCallId })
    }

    @Test
    fun `different purposes stay in separate subtrees`() {
        val parts = listOf(
            researchTool("search-1", "search_web", "Confirm the campus"),
            researchTool("search-2", "search_web", "Inspect dormitories"),
        )

        val steps = parts.singleThinkingBlock().steps

        assertEquals(2, steps.size)
        assertEquals(
            listOf("Confirm the campus", "Inspect dormitories"),
            steps.map { (it as ThinkingStep.ResearchPurposeStep).purpose },
        )
    }

    @Test
    fun `legacy research tools without a purpose remain flat`() {
        val parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "legacy",
                toolName = "search_web",
                input = """{"query":"campus"}""",
            )
        )

        val step = parts.singleThinkingBlock().steps.single()

        assertTrue(step is ThinkingStep.ToolStep)
    }

    @Test
    fun `blank research purpose remains flat`() {
        val parts = listOf(
            researchTool("search-1", "search_web", "  "),
        )

        val step = parts.singleThinkingBlock().steps.single()

        assertTrue(step is ThinkingStep.ToolStep)
    }

    @Test
    fun `purpose on an unrelated tool does not create a research subtree`() {
        val parts = listOf(
            researchTool("shell-1", "workspace_shell", "Inspect files"),
        )

        val step = parts.singleThinkingBlock().steps.single()

        assertTrue(step is ThinkingStep.ToolStep)
    }

    @Test
    fun `reasoning remains a boundary between repeated purposes`() {
        val parts = listOf(
            researchTool("search-1", "search_web", "Confirm the campus"),
            UIMessagePart.Reasoning("Reviewing the first result"),
            researchTool("search-2", "search_web", "Confirm the campus"),
        )

        val steps = parts.singleThinkingBlock().steps

        assertEquals(3, steps.size)
        assertTrue(steps[0] is ThinkingStep.ResearchPurposeStep)
        assertTrue(steps[1] is ThinkingStep.ReasoningStep)
        assertTrue(steps[2] is ThinkingStep.ResearchPurposeStep)
    }

    private fun researchTool(
        id: String,
        name: String,
        purpose: String,
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = buildJsonObject {
            put("query", "campus")
            put("purpose", purpose)
        }.toString(),
    )

    private fun List<UIMessagePart>.singleThinkingBlock(): MessagePartBlock.ThinkingBlock =
        groupMessageParts().single() as MessagePartBlock.ThinkingBlock
}
