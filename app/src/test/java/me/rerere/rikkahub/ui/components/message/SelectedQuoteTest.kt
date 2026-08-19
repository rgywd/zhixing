package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedQuoteTest {
    @Test
    fun `joins selected composables in visual order`() {
        val selectedTexts = listOf(
            AnnotatedString("first line"),
            AnnotatedString("second line"),
        )

        assertEquals("first line\nsecond line", buildSelectedQuoteText(selectedTexts))
    }

    @Test
    fun `trims only the outer selection whitespace`() {
        val selectedTexts = listOf(
            AnnotatedString("  first line"),
            AnnotatedString("second line  "),
        )

        assertEquals("first line\nsecond line", buildSelectedQuoteText(selectedTexts))
    }
}
