package me.rerere.rikkahub.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkUiStateTest {
    @Test
    fun `normalization never imports or invents repositories`() {
        val state = WorkUiState(
            mode = WorkAppMode.WORK,
            activeRepositoryId = "missing",
            repositories = emptyList(),
        ).normalized()

        assertEquals(WorkAppMode.WORK, state.mode)
        assertTrue(state.repositories.isEmpty())
        assertNull(state.activeRepositoryId)
    }

    @Test
    fun `normalization selects first explicitly configured repository`() {
        val first = WorkRepositoryConfig(id = "one", displayName = "Zhixing", path = "C:/src/zhixing")
        val second = WorkRepositoryConfig(id = "two", displayName = "Other", path = "C:/src/other")

        val state = WorkUiState(
            activeRepositoryId = "deleted",
            repositories = listOf(first, second),
        ).normalized()

        assertEquals("one", state.activeRepositoryId)
        assertEquals(first, state.activeRepository)
    }

    @Test
    fun `invalid empty repository rows are discarded`() {
        val valid = WorkRepositoryConfig(id = "valid", displayName = "Zhixing", path = "C:/src/zhixing")
        val state = WorkUiState(
            repositories = listOf(
                valid,
                WorkRepositoryConfig(id = "blank-name", displayName = " ", path = "C:/src/other"),
                WorkRepositoryConfig(id = "blank-path", displayName = "Other", path = " "),
            ),
        ).normalized()

        assertEquals(listOf(valid), state.repositories)
    }

    @Test
    fun `reasoning effort is remembered independently for each model`() {
        val preferences = WorkRepositoryPreferences()
            .withEffort("gpt-5.6-luna", "high")
            .withEffort("gpt-5.6-terra", "medium")

        assertEquals("high", preferences.effortFor("gpt-5.6-luna"))
        assertEquals("medium", preferences.effortFor("gpt-5.6-terra"))
        assertNull(preferences.effort)
    }

    @Test
    fun `schema one reasoning effort remains readable`() {
        val preferences = WorkRepositoryPreferences(model = "legacy", effort = "high")

        assertEquals("high", preferences.effortFor("legacy"))
    }
}
