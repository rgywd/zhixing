package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemoryState
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryRepositoryTest {
    @Test
    fun unknownPersistedValuesDegradeToContextAndActive() {
        val memory = MemoryEntity(
            id = 7,
            assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
            content = "Legacy value",
            kind = "FUTURE_KIND",
            state = "FUTURE_STATE",
        ).toAssistantMemory()

        assertEquals(MemoryKind.CONTEXT, memory.kind)
        assertEquals(MemoryState.ACTIVE, memory.state)
    }
}
