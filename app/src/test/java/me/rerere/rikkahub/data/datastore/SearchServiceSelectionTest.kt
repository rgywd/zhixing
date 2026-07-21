package me.rerere.rikkahub.data.datastore

import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SearchServiceSelectionTest {
    private val first = SearchServiceOptions.BingLocalOptions()
    private val second = SearchServiceOptions.DoubaoOptions(apiKey = "test")

    @Test
    fun `old single selection index migrates to provider id`() {
        assertEquals(
            setOf(second.id),
            resolveSearchServiceSelection(listOf(first, second), legacyIndex = 1),
        )
    }

    @Test
    fun `valid provider ids remain primary over legacy index`() {
        assertEquals(
            setOf(first.id, second.id),
            resolveSearchServiceSelection(listOf(first, second), setOf(first.id, second.id), legacyIndex = 0),
        )
    }

    @Test
    fun `removed provider selection falls back to a configured provider`() {
        assertEquals(
            setOf(second.id),
            resolveSearchServiceSelection(listOf(second), setOf(Uuid.random()), legacyIndex = 8),
        )
    }

    @Test
    fun `empty provider list has empty selection`() {
        assertTrue(resolveSearchServiceSelection(emptyList()).isEmpty())
    }
}
