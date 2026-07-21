package me.rerere.rikkahub.ui.pages.work

import me.rerere.rikkahub.data.work.PhoneWorkRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWorkChatInteractionTest {
    @Test
    fun `repository search keeps available matches grouped by discovery root`() {
        val repos = listOf(
            repo(id = "company", name = "backend", group = "Workspace"),
            repo(id = "personal", name = "notes", group = "Documents"),
            repo(id = "deleted", name = "backend-old", group = "Workspace", available = false),
        )

        val result = filterWorkRepos(repos, "work")

        assertEquals(listOf("Workspace"), result.keys.toList())
        assertEquals(listOf("company"), result.getValue("Workspace").map { it.id })
    }

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

    private fun repo(
        id: String,
        name: String,
        group: String,
        available: Boolean = true,
    ) = PhoneWorkRepo(
        id = id,
        runnerId = "runner",
        name = name,
        models = listOf("gpt-5.6-sol"),
        reasoningEfforts = listOf("high"),
        available = available,
        group = group,
    )
}
