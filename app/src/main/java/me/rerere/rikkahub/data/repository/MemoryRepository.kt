package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemoryState
import me.rerere.rikkahub.data.model.ProfileDimensions
import me.rerere.rikkahub.data.model.ProfileEvidence
import me.rerere.rikkahub.utils.JsonInstant

internal data class MemoryToolScope(
    val contextAssistantId: String,
)

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val PROFILE_PROMPT_LIMIT = 8
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
            .map { entities ->
                entities
                    .filterNot { it.state == MemoryState.DELETED.name }
                    .map(MemoryEntity::toAssistantMemory)
            }

    fun getAllGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        getAllMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)

    suspend fun getProfileMaintenanceMemories(): List<AssistantMemory> =
        memoryDAO.getAllMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map(MemoryEntity::toAssistantMemory)

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
        ProfileMemoryMutationGate.run {
            memoryDAO.deleteMemoriesOfAssistant(assistantId)
        }
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory = ProfileMemoryMutationGate.run {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        updateContent(old, content)
    }

    internal suspend fun updateToolMemoryContent(
        scope: MemoryToolScope,
        id: Int,
        content: String,
    ): AssistantMemory = ProfileMemoryMutationGate.run {
        updateContent(requireToolMemory(scope, id), content)
    }

    private suspend fun updateContent(old: MemoryEntity, content: String): AssistantMemory {
        old.requireNotDeleted()
        require(content.isNotBlank()) { "Memory content must not be blank" }
        val newMemory = old.copy(
            content = content,
            source = MemorySource.MANUAL.name,
            locked = true,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(newMemory)
        return newMemory.toAssistantMemory()
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        kind: MemoryKind = MemoryKind.CONTEXT,
        dimensionId: String = "",
        source: MemorySource = MemorySource.MANUAL,
        state: MemoryState = MemoryState.ACTIVE,
        confidence: Float = 1f,
        evidenceConversationIds: List<String> = emptyList(),
        profileEvidence: List<ProfileEvidence> = emptyList(),
        supportingObservationIds: List<Int> = emptyList(),
        canonicalKey: String = "",
        firstEvidenceAt: Long = 0,
        locked: Boolean = source != MemorySource.AUTO,
        lastEvidenceAt: Long = 0,
    ): AssistantMemory = ProfileMemoryMutationGate.run {
        require(content.isNotBlank()) { "Memory content must not be blank" }
        when (kind) {
            MemoryKind.PROFILE, MemoryKind.OBSERVATION -> require(
                dimensionId in ProfileDimensions.builtIn
            ) {
                "Memory dimension must be a built-in profile dimension"
            }

            MemoryKind.CONTEXT -> require(dimensionId.isBlank()) {
                "Context memory must not have a profile dimension"
            }
        }
        val now = System.currentTimeMillis()
        val entity = MemoryEntity(
            assistantId = if (kind == MemoryKind.PROFILE) GLOBAL_MEMORY_ID else assistantId,
            content = content,
            kind = kind.name,
            state = state.name,
            createdAt = now,
            updatedAt = now,
            dimensionId = dimensionId,
            confidence = confidence.coerceIn(0f, 1f),
            source = source.name,
            evidenceConversationIds = JsonInstant.encodeToString(evidenceConversationIds.distinct()),
            profileEvidenceJson = JsonInstant.encodeToString(profileEvidence.distinctBy(ProfileEvidence::messageId)),
            supportingObservationIds = JsonInstant.encodeToString(supportingObservationIds.distinct()),
            canonicalKey = canonicalKey,
            firstEvidenceAt = firstEvidenceAt,
            locked = locked,
            lastEvidenceAt = lastEvidenceAt,
        )
        entity.copy(
            id = memoryDAO.insertMemory(entity).toInt()
        ).toAssistantMemory()
    }

    suspend fun updateState(id: Int, state: MemoryState): AssistantMemory = ProfileMemoryMutationGate.run {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        updateState(old, state)
    }

    internal suspend fun restoreToolMemory(
        scope: MemoryToolScope,
        id: Int,
    ): AssistantMemory = ProfileMemoryMutationGate.run {
        updateState(requireToolMemory(scope, id), MemoryState.ACTIVE)
    }

    private suspend fun updateState(old: MemoryEntity, state: MemoryState): AssistantMemory {
        old.requireNotDeleted()
        require(state != MemoryState.DELETED) {
            "Deleted memory cannot change state"
        }
        val updated = old.copy(
            state = state.name,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(updated)
        return updated.toAssistantMemory()
    }

    suspend fun updateManualMemory(
        id: Int,
        content: String,
        dimensionId: String,
    ): AssistantMemory = ProfileMemoryMutationGate.run {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        old.requireNotDeleted()
        require(content.isNotBlank()) { "Memory content must not be blank" }
        if (old.kind == MemoryKind.PROFILE.name) {
            require(dimensionId in ProfileDimensions.builtIn) {
                "Memory dimension must be a built-in profile dimension"
            }
        }
        val updated = old.copy(
            content = content,
            dimensionId = if (old.kind == MemoryKind.PROFILE.name) dimensionId else "",
            source = MemorySource.MANUAL.name,
            confidence = 1f,
            locked = true,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(updated)
        updated.toAssistantMemory()
    }

    suspend fun confirmPending(id: Int): AssistantMemory = ProfileMemoryMutationGate.run {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        old.requireNotDeleted()
        require(old.state == MemoryState.PENDING.name) {
            "Only pending memory can be confirmed"
        }
        val updated = old.copy(
            state = MemoryState.ACTIVE.name,
            source = MemorySource.MANUAL.name,
            locked = true,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(updated)
        updated.toAssistantMemory()
    }

    suspend fun addAutoProfile(
        content: String,
        dimensionId: String,
        confidence: Float,
        evidenceConversationIds: List<String>,
        state: MemoryState,
        lastEvidenceAt: Long,
        profileEvidence: List<ProfileEvidence> = emptyList(),
        supportingObservationIds: List<Int> = emptyList(),
        firstEvidenceAt: Long = 0,
    ): AssistantMemory = addMemory(
        assistantId = GLOBAL_MEMORY_ID,
        content = content,
        kind = MemoryKind.PROFILE,
        dimensionId = dimensionId,
        source = MemorySource.AUTO,
        state = state,
        confidence = confidence,
        evidenceConversationIds = evidenceConversationIds,
        profileEvidence = profileEvidence,
        supportingObservationIds = supportingObservationIds,
        firstEvidenceAt = firstEvidenceAt,
        locked = false,
        lastEvidenceAt = lastEvidenceAt,
    )

    suspend fun updateAutoProfile(
        id: Int,
        content: String,
        dimensionId: String,
        confidence: Float,
        evidenceConversationIds: List<String>,
        state: MemoryState,
        lastEvidenceAt: Long,
        profileEvidence: List<ProfileEvidence> = emptyList(),
        supportingObservationIds: List<Int> = emptyList(),
        firstEvidenceAt: Long = 0,
    ): AssistantMemory? = ProfileMemoryMutationGate.run {
        val old = memoryDAO.getMemoryById(id) ?: return@run null
        if (
            old.assistantId != GLOBAL_MEMORY_ID ||
            old.kind != MemoryKind.PROFILE.name ||
            old.source != MemorySource.AUTO.name ||
            old.state == MemoryState.DELETED.name ||
            old.locked
        ) return@run null

        val updated = old.copy(
            content = content,
            dimensionId = dimensionId,
            confidence = confidence.coerceIn(0f, 1f),
            evidenceConversationIds = JsonInstant.encodeToString(evidenceConversationIds.distinct()),
            profileEvidenceJson = JsonInstant.encodeToString(profileEvidence.distinctBy(ProfileEvidence::messageId)),
            supportingObservationIds = JsonInstant.encodeToString(supportingObservationIds.distinct()),
            firstEvidenceAt = firstEvidenceAt,
            state = state.name,
            lastEvidenceAt = lastEvidenceAt,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(updated)
        updated.toAssistantMemory()
    }

    suspend fun archiveMemory(id: Int): AssistantMemory = ProfileMemoryMutationGate.run {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        archiveMemory(old)
    }

    internal suspend fun archiveToolMemory(
        scope: MemoryToolScope,
        id: Int,
    ): AssistantMemory = ProfileMemoryMutationGate.run {
        archiveMemory(requireToolMemory(scope, id))
    }

    private suspend fun archiveMemory(old: MemoryEntity): AssistantMemory {
        old.requireNotDeleted()
        val updated = old.copy(
            state = MemoryState.ARCHIVED.name,
            locked = old.locked ||
                (old.kind == MemoryKind.PROFILE.name && old.source == MemorySource.AUTO.name),
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(updated)
        return updated.toAssistantMemory()
    }

    suspend fun addAutoObservation(
        content: String,
        dimensionId: String,
        canonicalKey: String,
        confidence: Float,
        evidence: List<ProfileEvidence>,
        state: MemoryState,
    ): AssistantMemory = addMemory(
        assistantId = GLOBAL_MEMORY_ID,
        content = content,
        kind = MemoryKind.OBSERVATION,
        dimensionId = dimensionId,
        source = MemorySource.AUTO,
        state = state,
        confidence = confidence,
        evidenceConversationIds = evidence.map(ProfileEvidence::conversationId),
        profileEvidence = evidence,
        canonicalKey = canonicalKey,
        firstEvidenceAt = evidence.minOfOrNull(ProfileEvidence::observedAt) ?: 0,
        locked = false,
        lastEvidenceAt = evidence.maxOfOrNull(ProfileEvidence::observedAt) ?: 0,
    )

    suspend fun updateAutoObservation(
        id: Int,
        content: String,
        dimensionId: String,
        canonicalKey: String,
        confidence: Float,
        evidence: List<ProfileEvidence>,
        state: MemoryState,
    ): AssistantMemory? = ProfileMemoryMutationGate.run {
        val old = memoryDAO.getMemoryById(id) ?: return@run null
        if (
            old.assistantId != GLOBAL_MEMORY_ID ||
            old.kind != MemoryKind.OBSERVATION.name ||
            old.source != MemorySource.AUTO.name ||
            old.state == MemoryState.DELETED.name ||
            old.locked
        ) return@run null

        val updated = old.copy(
            content = content,
            dimensionId = dimensionId,
            canonicalKey = canonicalKey,
            confidence = confidence.coerceIn(0f, 1f),
            evidenceConversationIds = JsonInstant.encodeToString(
                evidence.map(ProfileEvidence::conversationId).distinct()
            ),
            profileEvidenceJson = JsonInstant.encodeToString(
                evidence.distinctBy(ProfileEvidence::messageId)
            ),
            state = state.name,
            firstEvidenceAt = evidence.minOfOrNull(ProfileEvidence::observedAt) ?: 0,
            lastEvidenceAt = evidence.maxOfOrNull(ProfileEvidence::observedAt) ?: 0,
            updatedAt = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(updated)
        updated.toAssistantMemory()
    }

    suspend fun deleteMemory(id: Int) = ProfileMemoryMutationGate.run {
        val target = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        if (target.state == MemoryState.DELETED.name) return@run
        val supportingIds = target.decodedSupportingObservationIds()
        val relatedRecords = if (supportingIds.isEmpty()) {
            emptyList()
        } else {
            memoryDAO.getAllMemoriesOfAssistant(GLOBAL_MEMORY_ID)
        }
        val now = System.currentTimeMillis()
        memoryDAO.updateMemories(
            deletionTombstones(
                target = target,
                supportingRecords = relatedRecords,
                now = now,
            ) + archivedAutomaticProfileSiblings(
                target = target,
                relatedRecords = relatedRecords,
                now = now,
            ),
        )
    }

    suspend fun revokeConversationEvidence(conversationId: String) = ProfileMemoryMutationGate.run {
        val memories = memoryDAO.getAllMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .filterNot { it.state == MemoryState.DELETED.name }
        val now = System.currentTimeMillis()
        val updates = linkedMapOf<Int, MemoryEntity>()
        memories.forEach { memory ->
            memory.withoutConversationEvidence(conversationId, now)?.let { updated ->
                updates[updated.id] = updated
            }
        }

        val invalidatedObservationIds = updates.values
            .filter {
                it.kind == MemoryKind.OBSERVATION.name &&
                    it.source == MemorySource.AUTO.name
            }
            .mapTo(hashSetOf(), MemoryEntity::id)
        if (invalidatedObservationIds.isNotEmpty()) {
            memories.filter { memory ->
                memory.kind == MemoryKind.PROFILE.name &&
                    memory.source == MemorySource.AUTO.name &&
                    !memory.locked &&
                    memory.decodedSupportingObservationIds().any(invalidatedObservationIds::contains)
            }.forEach { profile ->
                val current = updates[profile.id] ?: profile
                updates[profile.id] = current.copy(
                    state = MemoryState.ARCHIVED.name,
                    updatedAt = now,
                )
            }
        }

        if (updates.isNotEmpty()) {
            memoryDAO.updateMemories(updates.values.toList())
        }
    }

    internal suspend fun deleteToolMemory(scope: MemoryToolScope, id: Int) = ProfileMemoryMutationGate.run {
        requireToolMemory(scope, id)
        deleteMemory(id)
    }

    private suspend fun requireToolMemory(scope: MemoryToolScope, id: Int): MemoryEntity {
        val memory = memoryDAO.getMemoryById(id)
        if (memory == null || !memory.isVisibleIn(scope)) {
            error("Memory record #$id not found in current memory scope")
        }
        return memory
    }
}

private fun MemoryEntity.requireNotDeleted() {
    require(state != MemoryState.DELETED.name) {
        "Deleted memory cannot be modified"
    }
}

private fun MemoryEntity.isVisibleIn(scope: MemoryToolScope): Boolean {
    if (state != MemoryState.ACTIVE.name && state != MemoryState.ARCHIVED.name) return false
    return when (kind) {
        MemoryKind.PROFILE.name -> assistantId == MemoryRepository.GLOBAL_MEMORY_ID
        MemoryKind.CONTEXT.name -> assistantId == scope.contextAssistantId
        else -> false
    }
}

internal fun MemoryEntity.toAssistantMemory(): AssistantMemory = AssistantMemory(
    id = id,
    content = content,
    kind = runCatching { MemoryKind.valueOf(kind) }.getOrDefault(MemoryKind.CONTEXT),
    state = runCatching { MemoryState.valueOf(state) }.getOrDefault(MemoryState.ACTIVE),
    createdAt = createdAt,
    updatedAt = updatedAt,
    dimensionId = dimensionId,
    confidence = confidence.coerceIn(0f, 1f),
    source = runCatching { MemorySource.valueOf(source) }.getOrDefault(MemorySource.LEGACY),
    evidenceConversationIds = runCatching {
        JsonInstant.decodeFromString<List<String>>(evidenceConversationIds)
    }.getOrDefault(emptyList()),
    profileEvidence = runCatching {
        JsonInstant.decodeFromString<List<ProfileEvidence>>(profileEvidenceJson)
    }.getOrDefault(emptyList()),
    supportingObservationIds = runCatching {
        JsonInstant.decodeFromString<List<Int>>(supportingObservationIds)
    }.getOrDefault(emptyList()),
    canonicalKey = canonicalKey,
    firstEvidenceAt = firstEvidenceAt,
    locked = locked,
    lastEvidenceAt = lastEvidenceAt,
)
