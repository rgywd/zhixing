package me.rerere.rikkahub.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWorkRepoPreferencesTest {
    @Test
    fun `mark recent moves repository to front and keeps five unique entries`() {
        val initial = PhoneWorkRepoPreferences(
            recent = (1..5).map { key("repo-$it") },
        )

        val updated = initial.markRecent(key("repo-3")).markRecent(key("repo-6"))

        assertEquals(
            listOf("repo-6", "repo-3", "repo-1", "repo-2", "repo-4"),
            updated.recent.map { it.repoId },
        )
    }

    @Test
    fun `toggle pinned adds newest first and removes an existing pin`() {
        val initial = PhoneWorkRepoPreferences(pinned = listOf(key("alpha")))

        val added = initial.togglePinned(key("beta"))
        val removed = added.togglePinned(key("alpha"))

        assertEquals(listOf("beta", "alpha"), added.pinned.map { it.repoId })
        assertEquals(listOf("beta"), removed.pinned.map { it.repoId })
    }

    @Test
    fun `default repository prefers latest available recent then a pin`() {
        val repos = listOf(
            repo("pinned"),
            repo("recent-old"),
            repo("recent-gone", available = false),
        )
        val preferences = PhoneWorkRepoPreferences(
            pinned = listOf(key("pinned")),
            recent = listOf(key("recent-gone"), key("recent-old")),
        )

        assertEquals("recent-old", chooseDefaultWorkRepo(repos, preferences)?.id)
        assertEquals(
            "pinned",
            chooseDefaultWorkRepo(repos, preferences.copy(recent = listOf(key("recent-gone"))))?.id,
        )
    }

    @Test
    fun `default repository stays empty without user history`() {
        assertNull(chooseDefaultWorkRepo(listOf(repo("alpha")), PhoneWorkRepoPreferences()))
        assertTrue(chooseDefaultWorkRepo(emptyList(), PhoneWorkRepoPreferences()) == null)
    }

    private fun key(repoId: String) = PhoneWorkRepoKey(runnerId = "runner", repoId = repoId)

    private fun repo(id: String, available: Boolean = true) = PhoneWorkRepo(
        id = id,
        runnerId = "runner",
        name = id,
        models = listOf("gpt-5.6-sol"),
        reasoningEfforts = listOf("high"),
        available = available,
    )
}
