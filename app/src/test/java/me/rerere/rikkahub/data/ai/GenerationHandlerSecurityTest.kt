package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerSecurityTest {
    @Test
    fun toolExecutionLogContainsOnlyToolIdentity() {
        val message = toolExecutionLogMessage("memory_tool")

        assertEquals("generateText: executing tool memory_tool", message)
        assertFalse(message.contains("content"))
        assertFalse(message.contains("args"))
    }

    @Test
    fun memoryPromptSnapshotRefreshesAfterSuccessfulToolMutation() = runBlocking {
        val initial = listOf(AssistantMemory(1, "old", MemoryKind.PROFILE))
        val refreshed = listOf(AssistantMemory(2, "new", MemoryKind.PROFILE))
        val snapshot = MemoryPromptSnapshot(initial)
        var refreshCount = 0

        assertEquals(initial, snapshot.resolve { error("must not refresh an unchanged snapshot") })

        snapshot.invalidate()
        assertEquals(
            refreshed,
            snapshot.resolve {
                refreshCount += 1
                refreshed
            },
        )
        assertEquals(
            refreshed,
            snapshot.resolve {
                refreshCount += 1
                error("must not refresh the same snapshot twice")
            },
        )
        assertEquals(1, refreshCount)
    }

    @Test
    fun toolInputIsSanitizedBeforeItCanBePersisted() {
        val tool = Tool(
            name = "sensitive_tool",
            description = "",
            sanitizeInputForStorage = { """{"safe_summary":"kept"}""" },
            execute = { emptyList() },
        )
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = tool.name,
                        input = """{"raw_text":"must not persist"}""",
                    ),
                ),
            ),
        )

        val sanitized = sanitizeToolInputsForStorage(messages, listOf(tool))
            .single()
            .parts
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
            .input

        assertEquals("""{"safe_summary":"kept"}""", sanitized)
        assertFalse(sanitized.contains("must not persist"))
    }

    @Test
    fun failingToolInputSanitizerFailsClosed() {
        val tool = Tool(
            name = "sensitive_tool",
            description = "",
            sanitizeInputForStorage = { error("sanitizer failure") },
            execute = { emptyList() },
        )
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = tool.name,
                        input = """{"raw_text":"must not persist"}""",
                    ),
                ),
            ),
        )

        val sanitized = sanitizeToolInputsForStorage(messages, listOf(tool))
            .single()
            .parts
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
            .input

        assertEquals("{}", sanitized)
        assertTrue(sanitized.length <= 2)
    }
}
