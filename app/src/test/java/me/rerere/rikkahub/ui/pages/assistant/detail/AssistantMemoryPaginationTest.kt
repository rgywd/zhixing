package me.rerere.rikkahub.ui.pages.assistant.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantMemoryPaginationTest {
    @Test
    fun `each page contains at most five items`() {
        val firstPage = paginateItems((1..12).toList(), requestedPage = 0)
        val lastPage = paginateItems((1..12).toList(), requestedPage = 2)

        assertEquals(listOf(1, 2, 3, 4, 5), firstPage.items)
        assertEquals(listOf(11, 12), lastPage.items)
        assertEquals(3, lastPage.totalPages)
    }

    @Test
    fun `page is clamped after items are removed`() {
        val page = paginateItems((1..6).toList(), requestedPage = 8)

        assertEquals(1, page.page)
        assertEquals(listOf(6), page.items)
    }

    @Test
    fun `empty list still exposes one stable page`() {
        val page = paginateItems(emptyList<Int>(), requestedPage = -2)

        assertEquals(0, page.page)
        assertEquals(1, page.totalPages)
        assertEquals(emptyList<Int>(), page.items)
    }
}
