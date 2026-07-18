package me.rerere.rikkahub.ui.pages.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayUrlEditorStateTest {
    @Test
    fun `saved normalized relay clears changed state even when draft text is already normalized`() {
        val state = RelayUrlEditorState("https://api.cluster-fluster.com")
        val relay = "https://happy.8-208-118-119.sslip.io"

        state.update(relay)
        assertTrue(state.changed)

        state.markSaved(relay)
        assertFalse(state.changed)
    }
}
