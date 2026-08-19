package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalDateTime
import me.rerere.rikkahub.ui.components.message.SelectedQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteShareTest {
    @Test
    fun `uses progressively smaller type for longer quotes`() {
        assertEquals(24.sp, quoteFontSizeFor("a".repeat(42)))
        assertEquals(21.sp, quoteFontSizeFor("a".repeat(43)))
        assertEquals(18.sp, quoteFontSizeFor("a".repeat(81)))
        assertEquals(15.sp, quoteFontSizeFor("a".repeat(131)))
        assertEquals(13.sp, quoteFontSizeFor("a".repeat(181)))
    }

    @Test
    fun `formats card date and timestamp down to seconds`() {
        val quote = SelectedQuote(
            text = "知行合一",
            modelName = null,
            createdAt = LocalDateTime(2026, 8, 19, 16, 27, 43),
        )

        assertEquals("08 / 19", quoteDateLabel(quote))
        assertEquals("16:27:43", quoteTimestampLabel(quote))
    }

    @Test
    fun `limits image cards without blocking text sharing`() {
        assertTrue(canShareQuoteAsImage("a".repeat(MAX_QUOTE_CARD_CHARACTERS)))
        assertFalse(canShareQuoteAsImage("a".repeat(MAX_QUOTE_CARD_CHARACTERS + 1)))
    }
}
