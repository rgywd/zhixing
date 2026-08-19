package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteShareTest {
    @Test
    fun `uses progressively smaller type for longer quotes`() {
        assertEquals(30.sp, quoteFontSizeFor("a".repeat(42)))
        assertEquals(25.sp, quoteFontSizeFor("a".repeat(43)))
        assertEquals(20.sp, quoteFontSizeFor("a".repeat(81)))
        assertEquals(16.sp, quoteFontSizeFor("a".repeat(131)))
        assertEquals(14.sp, quoteFontSizeFor("a".repeat(181)))
    }

    @Test
    fun `limits image cards without blocking text sharing`() {
        assertTrue(canShareQuoteAsImage("a".repeat(MAX_QUOTE_CARD_CHARACTERS)))
        assertFalse(canShareQuoteAsImage("a".repeat(MAX_QUOTE_CARD_CHARACTERS + 1)))
    }
}
