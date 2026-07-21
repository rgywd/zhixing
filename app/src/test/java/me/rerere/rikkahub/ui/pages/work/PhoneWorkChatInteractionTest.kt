package me.rerere.rikkahub.ui.pages.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWorkChatInteractionTest {
    @Test
    fun `quote prefixes every line and leaves cursor spacing`() {
        assertEquals("> first\n> second\n\n", mergeWorkQuote("", "first\nsecond"))
        assertEquals("draft\n\n> reply\n\n", mergeWorkQuote("draft  ", "reply"))
    }

    @Test
    fun `new items follow when user was at bottom`() {
        val decision = decideWorkScroll(previousItemCount = 5, newItemCount = 7, lastVisibleIndex = 4)

        assertTrue(decision.followLatest)
        assertEquals(0, decision.addedItems)
    }

    @Test
    fun `new items accumulate when user is reading history`() {
        val decision = decideWorkScroll(previousItemCount = 5, newItemCount = 7, lastVisibleIndex = 2)

        assertFalse(decision.followLatest)
        assertEquals(2, decision.addedItems)
    }

    @Test
    fun `removed status rows do not create unread messages`() {
        val decision = decideWorkScroll(previousItemCount = 7, newItemCount = 6, lastVisibleIndex = 2)

        assertFalse(decision.followLatest)
        assertEquals(0, decision.addedItems)
    }
}
