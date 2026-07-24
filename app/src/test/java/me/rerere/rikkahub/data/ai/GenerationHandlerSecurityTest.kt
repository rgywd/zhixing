package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
