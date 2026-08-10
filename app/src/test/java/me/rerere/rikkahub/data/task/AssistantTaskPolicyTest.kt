package me.rerere.rikkahub.data.task

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTaskPolicyTest {
    private val json = Json

    @Test
    fun `single read stays chat while second tool becomes a visible task`() {
        val firstRead = step(toolName = "search_web", ordinal = 1)
        val secondRead = step(toolName = "knowledge_search", ordinal = 2)

        assertFalse(firstRead.requiresVisibleTask(json))
        assertTrue(secondRead.requiresVisibleTask(json))
        assertFalse(secondRead.requiresDurableTask(json))
    }

    @Test
    fun `writes external calls and user questions require durable tasks`() {
        assertTrue(step("task_create").requiresDurableTask(json))
        assertTrue(step("gh").requiresDurableTask(json))
        assertTrue(step("mcp__life__digest").requiresDurableTask(json))
        assertTrue(step("ask_user", requiresUserAnswer = true).requiresDurableTask(json))
        assertTrue(
            step(
                toolName = "monthly_spending_summary",
                input = """{"action":"save"}""",
            ).requiresDurableTask(json)
        )
        assertFalse(
            step(
                toolName = "monthly_spending_summary",
                input = """{"action":"query"}""",
            ).requiresDurableTask(json)
        )
    }

    @Test
    fun `terminal states cannot move backwards and retry increments only from failure`() {
        assertTrue(
            canTransitionAssistantTask(
                AssistantTaskStatus.RUNNING,
                AssistantTaskStatus.WAITING_FOR_INPUT,
            )
        )
        assertTrue(
            canTransitionAssistantTask(
                AssistantTaskStatus.FAILED_RETRYABLE,
                AssistantTaskStatus.RUNNING,
            )
        )
        assertFalse(
            canTransitionAssistantTask(
                AssistantTaskStatus.COMPLETED,
                AssistantTaskStatus.RUNNING,
            )
        )
        assertFalse(
            canTransitionAssistantTask(
                AssistantTaskStatus.STOPPED,
                AssistantTaskStatus.RUNNING,
            )
        )
    }

    @Test
    fun `event text and references are bounded and redact common credentials`() {
        val text = sanitizeUserFacingText(
            "Authorization: secret-value\n正在创建事项",
            fallback = "fallback",
        )
        val ref = sanitizeReference("https://example.com/result?token=secret#fragment")

        assertFalse(text.contains("secret-value"))
        assertEquals("https://example.com/result", ref)
        assertTrue(text.length <= 160)
    }

    private fun step(
        toolName: String,
        ordinal: Int = 1,
        input: String = "{}",
        requiresUserAnswer: Boolean = false,
    ) = AssistantTaskStep(
        toolName = toolName,
        toolCallId = "call-$ordinal",
        input = input,
        ordinal = ordinal,
        requiresUserAnswer = requiresUserAnswer,
        hasUserAnswer = false,
    )
}
