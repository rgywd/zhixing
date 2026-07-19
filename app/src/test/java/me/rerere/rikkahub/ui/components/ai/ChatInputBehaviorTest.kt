package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInputBehaviorTest {
    @Test
    fun `normal chat keeps stop behavior while loading`() {
        assertTrue(shouldStopOnSend(loading = true, canSend = true, allowSendWhileLoading = false))
    }

    @Test
    fun `Codex running turn sends a non-empty draft as steer`() {
        assertFalse(shouldStopOnSend(loading = true, canSend = true, allowSendWhileLoading = true))
        assertTrue(shouldStopOnSend(loading = true, canSend = false, allowSendWhileLoading = true))
    }
}
