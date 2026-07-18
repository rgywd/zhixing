package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemoryState

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val PROFILE_PROMPT_LIMIT = 32
        const val CONTEXT_PROMPT_LIMIT = 20
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map(MemoryEntity::toAssistantMemory)
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map(MemoryEntity::toAssistantMemory)
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map(MemoryEntity::toAssistantMemory)
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map(MemoryEntity::toAssistantMemory)
    }

    fun getAllMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getAllMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map(MemoryEntity::toAssistantMemory) }

    fun getAllGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        getAllMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)

    suspend fun getPromptMemories(contextAssistantId: String): List<AssistantMemory> {
        val profile = memoryDAO.getActiveMemoriesOfKind(
            assistantId = GLOBAL_MEMORY_ID,
            kind = MemoryKind.PROFILE.name,
            limit = PROFILE_PROMPT_LIMIT,
        )
        val context = memoryDAO.getActiveMemoriesOfKind(
            assistantId = contextAssistantId,
            kind = MemoryKind.CONTEXT.name,
            limit = CONTEXT_PROMPT_LIMIT,
        )
        return (profile + context).map(MemoryEntity::toAssistantMemory)
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory = old.copy(
            content = content,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(newMemory)
        return newMemory.toAssistantMemory()
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        kind: MemoryKind = MemoryKind.CONTEXT,
    ): AssistantMemory {
        val now = System.currentTimeMillis()
        val entity = MemoryEntity(
            assistantId = if (kind == MemoryKind.PROFILE) GLOBAL_MEMORY_ID else assistantId,
            content = content,
            kind = kind.name,
            state = MemoryState.ACTIVE.name,
            createdAt = now,
            updatedAt = now,
        )
        return entity.copy(
            id = memoryDAO.insertMemory(entity).toInt()
        ).toAssistantMemory()
    }

    suspend fun updateState(id: Int, state: MemoryState): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val updated = old.copy(
            state = state.name,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(updated)
        return updated.toAssistantMemory()
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }
}

internal fun MemoryEntity.toAssistantMemory(): AssistantMemory = AssistantMemory(
    id = id,
    content = content,
    kind = runCatching { MemoryKind.valueOf(kind) }.getOrDefault(MemoryKind.CONTEXT),
    state = runCatching { MemoryState.valueOf(state) }.getOrDefault(MemoryState.ACTIVE),
    createdAt = createdAt,
    updatedAt = updatedAt,
)
