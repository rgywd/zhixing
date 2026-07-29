package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CompressPromptTest {
    @Test
    fun `legacy default is upgraded to checkpoint prompt`() {
        assertNotEquals(LEGACY_DEFAULT_COMPRESS_PROMPT, DEFAULT_COMPRESS_PROMPT)
        assertEquals(DEFAULT_COMPRESS_PROMPT, resolveCompressPrompt(LEGACY_DEFAULT_COMPRESS_PROMPT))
    }

    @Test
    fun `custom prompt is preserved`() {
        assertEquals("my custom prompt", resolveCompressPrompt("my custom prompt"))
    }
}
