package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningLevelTest {
    @Test
    fun `selectable levels replace auto with max`() {
        assertEquals(
            listOf(
                ReasoningLevel.OFF,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
                ReasoningLevel.XHIGH,
                ReasoningLevel.MAX,
            ),
            ReasoningLevel.selectableEntries,
        )
        assertFalse(ReasoningLevel.AUTO in ReasoningLevel.selectableEntries)
    }

    @Test
    fun `legacy auto normalizes to medium while max keeps native wire values`() {
        assertEquals(ReasoningLevel.MEDIUM, ReasoningLevel.AUTO.normalizedForChat)
        assertEquals(ReasoningLevel.MAX, ReasoningLevel.MAX.normalizedForChat)
        assertEquals("max", ReasoningLevel.MAX.effort)
        assertEquals(32_000, ReasoningLevel.MAX.budgetTokens)
        assertTrue(ReasoningLevel.MAX.isEnabled)
    }
}
