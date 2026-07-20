package me.rerere.rikkahub.data.work

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class WorkUiStateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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

    @Test
    fun `repository thread pointers are isolated by Codex connection`() = runBlocking {
        val file = temporaryFolder.newFile("work-connections.json")
        file.delete()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val repository = WorkRepositoryConfig(
            id = "one",
            displayName = "Zhixing",
            path = "C:/src/zhixing",
            currentThreadId = "legacy-thread",
        )
        val store = FileWorkUiStore(file, json)
        store.upsertRepository(repository)

        store.bindRepositoryConnection(repository.id, "connection-a")
        assertEquals("legacy-thread", store.state.value.activeRepository?.threadIdFor("connection-a"))
        store.updateDirectThread(repository.id, "connection-a", "thread-a")
        store.bindRepositoryConnection(repository.id, "connection-b")
        assertNull(store.state.value.activeRepository?.currentThreadId)
        // A delayed thread/start response from A may still be recorded, but must not
        // switch the repository away from the already active B connection.
        store.updateDirectThread(repository.id, "connection-a", "thread-a-late")
        assertEquals("connection-b", store.state.value.activeRepository?.connectionId)
        assertNull(store.state.value.activeRepository?.currentThreadId)
        store.updateDirectThread(repository.id, "connection-b", "thread-b")
        store.clearCurrentThread(repository.id)
        assertEquals("thread-a-late", store.state.value.activeRepository?.threadIdFor("connection-a"))
        assertNull(store.state.value.activeRepository?.threadIdFor("connection-b"))
        store.updateDirectThread(repository.id, "connection-b", "thread-b")

        val restored = FileWorkUiStore(file, json).state.value.activeRepository
        assertEquals(3, FileWorkUiStore(file, json).state.value.schema)
        assertEquals("thread-a-late", restored?.threadIdFor("connection-a"))
        assertEquals("thread-b", restored?.threadIdFor("connection-b"))
        assertEquals("connection-b", restored?.connectionId)
    }

    @Test
    fun `file store persists add edit selection mode and local-only delete`() = runBlocking {
        val file = temporaryFolder.newFile("work-ui.json")
        file.delete() // A fresh install has no state file yet.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val first = WorkRepositoryConfig(
            id = "one",
            displayName = "Zhixing",
            path = "C:/src/zhixing",
            currentThreadId = "thread-one",
        )
        val second = WorkRepositoryConfig(
            id = "two",
            displayName = "Other",
            path = "C:/src/other",
            currentThreadId = "thread-two",
        )
        val store = FileWorkUiStore(file, json)

        store.upsertRepository(first)
        store.upsertRepository(second)
        store.selectRepository(second.id)
        store.setMode(WorkAppMode.WORK)
        store.upsertRepository(second.copy(displayName = "Other edited", path = "D:/src/other"))
        store.removeRepository(second.id)

        val restored = FileWorkUiStore(file, json).state.value
        assertEquals(WorkAppMode.WORK, restored.mode)
        assertEquals(first.id, restored.activeRepositoryId)
        assertEquals(listOf(first), restored.repositories)
        // Removing a repository only removes its local mapping. Another repository's
        // remote thread identity remains untouched and can still be resumed.
        assertEquals("thread-one", restored.activeRepository?.currentThreadId)
    }
}
