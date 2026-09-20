package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class SettingsMergeTest {
    @Test fun `stale UI edits preserve newly created agents and unrelated settings`() {
        val a = Assistant(name = "Main")
        val child = Assistant(name = "Child", managedBy = a.id)
        val base = Settings(assistants = listOf(a))
        val live = base.copy(revision = 1, assistants = listOf(a.copy(configRevision = 1, systemPrompt = "new prompt"), child))
        val merged = mergeSettingsSnapshots(base, live, base.copy(dynamicColor = false))
        assertFalse(merged.dynamicColor)
        assertEquals(live.assistants, merged.assistants)
        val nameChange = mergeSettingsSnapshots(base, live, base.copy(assistants = listOf(a.copy(name = "Renamed"))))
        assertEquals("new prompt", nameChange.assistants.first().systemPrompt)
        assertEquals("Renamed", nameChange.assistants.first().name)
        assertEquals(child, nameChange.assistants.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `concurrent conflicting prompt edits are not silently overwritten`() {
        val a = Assistant(name = "Main")
        val base = Settings(assistants = listOf(a))
        mergeSettingsSnapshots(base, base.copy(assistants = listOf(a.copy(systemPrompt = "agent edit"))),
            base.copy(assistants = listOf(a.copy(systemPrompt = "UI edit"))))
    }

    @Test fun `an unchanged stale record cannot resurrect a deleted agent`() {
        val a = Assistant(name = "Main"); val b = Assistant(name = "Removed")
        val base = Settings(assistants = listOf(a, b))
        val merged = mergeSettingsSnapshots(base, base.copy(assistants = listOf(a)), base.copy(dynamicColor = false))
        assertEquals(listOf(a), merged.assistants)
    }
}
