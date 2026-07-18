package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemoryState
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPromptsTest {
    @Test
    fun memoryPromptSeparatesProfileAndContextAndExcludesArchivedRecords() {
        val prompt = buildMemoryPrompt(
            listOf(
                AssistantMemory(1, "User prefers Chinese.", MemoryKind.PROFILE),
                AssistantMemory(2, "User has a meeting tomorrow.", MemoryKind.CONTEXT),
                AssistantMemory(3, "Archived detail.", MemoryKind.CONTEXT, MemoryState.ARCHIVED),
            )
        )

        assertTrue(prompt.contains("User profile:"))
        assertTrue(prompt.contains("User prefers Chinese."))
        assertTrue(prompt.contains("Remembered context:"))
        assertTrue(prompt.contains("User has a meeting tomorrow."))
        assertFalse(prompt.contains("Archived detail."))
    }

    @Test
    fun promptSelectionAppliesIndependentProfileAndContextLimits() {
        val memories = buildList {
            repeat(MemoryRepository.PROFILE_PROMPT_LIMIT + 5) { index ->
                add(AssistantMemory(index, "profile-$index", MemoryKind.PROFILE))
            }
            repeat(MemoryRepository.CONTEXT_PROMPT_LIMIT + 5) { index ->
                add(AssistantMemory(100 + index, "context-$index", MemoryKind.CONTEXT))
            }
        }

        val selected = selectMemoriesForPrompt(memories)

        assertEquals(
            MemoryRepository.PROFILE_PROMPT_LIMIT,
            selected.count { it.kind == MemoryKind.PROFILE },
        )
        assertEquals(
            MemoryRepository.CONTEXT_PROMPT_LIMIT,
            selected.count { it.kind == MemoryKind.CONTEXT },
        )
    }
}
