package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexCommandCompletionProviderTest {
    @Test
    fun `empty slash includes native app server commands before catalog entries`() = runBlocking {
        val result = CodexCommandCompletionProvider(emptyList(), emptyList(), emptyList()).complete(
            ChatCompletionContext("/", TextRange(1)),
        )

        assertEquals(listOf("/compact", "/new"), result?.items?.map { it.label })
        assertTrue(result?.items?.all { it.sortScore == 400 } == true)
    }
}
