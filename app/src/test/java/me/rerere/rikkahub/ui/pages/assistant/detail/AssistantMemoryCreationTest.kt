package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.ProfileDimensions
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantMemoryCreationTest {
    @Test
    fun `profile creation preserves selected dimension`() {
        val memory = AssistantMemory(
            id = 0,
            content = "User prefers concise replies.",
            kind = MemoryKind.PROFILE,
            dimensionId = ProfileDimensions.PREFERENCES_VALUES,
        )

        assertEquals(
            ProfileDimensions.PREFERENCES_VALUES,
            memoryDimensionIdForCreate(memory),
        )
    }

    @Test
    fun `context creation never persists a profile dimension`() {
        val memory = AssistantMemory(
            id = 0,
            content = "Remember this context.",
            kind = MemoryKind.CONTEXT,
            dimensionId = ProfileDimensions.IDENTITY_CONTEXT,
        )

        assertEquals("", memoryDimensionIdForCreate(memory))
    }
}
