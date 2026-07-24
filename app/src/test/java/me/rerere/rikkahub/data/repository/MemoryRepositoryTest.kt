package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemoryState
import me.rerere.rikkahub.data.model.ProfileDimensions
import me.rerere.rikkahub.data.model.ProfileEvidence
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun toolScopeAllowsGlobalProfilesAndCurrentContextOnly() = runBlocking {
        val dao = FakeMemoryDao(
            MemoryEntity(
                id = 1,
                assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
                content = "Global profile",
                kind = MemoryKind.PROFILE.name,
                source = MemorySource.AUTO.name,
            ),
            MemoryEntity(
                id = 2,
                assistantId = "assistant-a",
                content = "Current context",
                kind = MemoryKind.CONTEXT.name,
            ),
        )
        val repository = MemoryRepository(dao)
        val scope = MemoryToolScope(contextAssistantId = "assistant-a")

        val profile = repository.updateToolMemoryContent(scope, id = 1, content = "Updated profile")
        assertEquals("Updated profile", profile.content)
        assertEquals(MemorySource.MANUAL, profile.source)
        assertTrue(profile.locked)

        val archived = repository.archiveToolMemory(scope, id = 2)
        assertEquals(MemoryState.ARCHIVED, archived.state)
        val restored = repository.restoreToolMemory(scope, id = 2)
        assertEquals(MemoryState.ACTIVE, restored.state)

        repository.deleteToolMemory(scope, id = 2)
        val deleted = dao.getMemoryById(2)
        assertEquals(MemoryState.DELETED.name, deleted?.state)
        assertEquals("", deleted?.content)
        assertEquals("[]", deleted?.evidenceConversationIds)
        assertEquals("[]", deleted?.profileEvidenceJson)
        assertToolScopeNotFound(2) {
            repository.restoreToolMemory(scope, id = 2)
        }
    }

    @Test
    fun toolScopeRejectsOtherScopesHiddenKindsAndMissingRecordsUniformly() = runBlocking {
        val dao = FakeMemoryDao(
            MemoryEntity(
                id = 3,
                assistantId = "assistant-b",
                content = "Other assistant context",
                kind = MemoryKind.CONTEXT.name,
            ),
            MemoryEntity(
                id = 4,
                assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
                content = "Hidden observation",
                kind = MemoryKind.OBSERVATION.name,
            ),
            MemoryEntity(
                id = 5,
                assistantId = "assistant-a",
                content = "Corrupt scoped profile",
                kind = MemoryKind.PROFILE.name,
            ),
            MemoryEntity(
                id = 6,
                assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
                content = "Global context outside current scope",
                kind = MemoryKind.CONTEXT.name,
            ),
            MemoryEntity(
                id = 8,
                assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
                content = "Pending profile is not model-visible",
                kind = MemoryKind.PROFILE.name,
                state = MemoryState.PENDING.name,
            ),
        )
        val repository = MemoryRepository(dao)
        val scope = MemoryToolScope(contextAssistantId = "assistant-a")

        for (id in listOf(3, 4, 5, 6, 8, 999)) {
            assertToolScopeNotFound(id) {
                repository.updateToolMemoryContent(scope, id, "changed")
            }
            assertToolScopeNotFound(id) {
                repository.archiveToolMemory(scope, id)
            }
            assertToolScopeNotFound(id) {
                repository.restoreToolMemory(scope, id)
            }
            assertToolScopeNotFound(id) {
                repository.deleteToolMemory(scope, id)
            }
        }

        assertEquals("Other assistant context", dao.getMemoryById(3)?.content)
        assertEquals(MemoryState.ACTIVE.name, dao.getMemoryById(4)?.state)
        assertNotNull(dao.getMemoryById(5))
        assertNotNull(dao.getMemoryById(6))
        assertNotNull(dao.getMemoryById(8))
    }

    @Test
    fun globalContextScopeCanAccessGlobalContext() = runBlocking {
        val dao = FakeMemoryDao(
            MemoryEntity(
                id = 7,
                assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
                content = "Global context",
                kind = MemoryKind.CONTEXT.name,
            ),
        )
        val repository = MemoryRepository(dao)

        val updated = repository.updateToolMemoryContent(
            scope = MemoryToolScope(MemoryRepository.GLOBAL_MEMORY_ID),
            id = 7,
            content = "Updated global context",
        )

        assertEquals("Updated global context", updated.content)
    }

    @Test
    fun deletingAutomaticProfileScrubsAndSuppressesItsSupportingObservations() = runBlocking {
        val observation = MemoryEntity(
            id = 11,
            assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
            content = "用户偏好先给结论",
            kind = MemoryKind.OBSERVATION.name,
            state = MemoryState.ACTIVE.name,
            dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION,
            source = MemorySource.AUTO.name,
            canonicalKey = "用户偏好先给结论",
            profileEvidenceJson = JsonInstant.encodeToString(
                listOf(ProfileEvidence("c1", "m1", "先给结论", 10))
            ),
        )
        val profile = MemoryEntity(
            id = 12,
            assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
            content = "用户倾向先查看结论。",
            kind = MemoryKind.PROFILE.name,
            state = MemoryState.ACTIVE.name,
            dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION,
            source = MemorySource.AUTO.name,
            supportingObservationIds = JsonInstant.encodeToString(listOf(11)),
        )
        val dao = FakeMemoryDao(observation, profile)
        val repository = MemoryRepository(dao)

        repository.deleteMemory(profile.id)

        val deletedProfile = dao.getMemoryById(profile.id)
        val deletedObservation = dao.getMemoryById(observation.id)
        assertEquals(MemoryState.DELETED.name, deletedProfile?.state)
        assertEquals("", deletedProfile?.content)
        assertEquals(MemoryState.DELETED.name, deletedObservation?.state)
        assertEquals("", deletedObservation?.content)
        assertEquals(
            "用户偏好先给结论".memorySuppressionKey(),
            deletedObservation?.canonicalKey,
        )
        assertTrue(repository.getPromptMemories(MemoryRepository.GLOBAL_MEMORY_ID).isEmpty())
        assertEquals(
            setOf(11, 12),
            repository.getProfileMaintenanceMemories().map { it.id }.toSet(),
        )
    }

    @Test
    fun deletedMemoryIsAnImmutableTerminalState() = runBlocking {
        val deleted = MemoryEntity(
            id = 18,
            assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
            content = "",
            kind = MemoryKind.PROFILE.name,
            state = MemoryState.DELETED.name,
            dimensionId = ProfileDimensions.PREFERENCES_VALUES,
            source = MemorySource.AUTO.name,
            canonicalKey = "claim".memorySuppressionKey(),
            locked = true,
        )
        val dao = FakeMemoryDao(deleted)
        val repository = MemoryRepository(dao)

        val failures = listOf(
            runCatching { repository.updateContent(18, "revived") }.exceptionOrNull(),
            runCatching {
                repository.updateManualMemory(
                    id = 18,
                    content = "revived",
                    dimensionId = ProfileDimensions.PREFERENCES_VALUES,
                )
            }.exceptionOrNull(),
            runCatching { repository.confirmPending(18) }.exceptionOrNull(),
            runCatching { repository.archiveMemory(18) }.exceptionOrNull(),
            runCatching { repository.updateState(18, MemoryState.ACTIVE) }.exceptionOrNull(),
        )

        assertTrue(failures.all { it?.message == "Deleted memory cannot be modified" })
        assertEquals(deleted, dao.getMemoryById(18))
    }

    @Test
    fun deletingProfileArchivesUnlockedAutomaticSiblingAndKeepsObservationTombstoneIdempotent() =
        runBlocking {
        val observation = MemoryEntity(
            id = 19,
            assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
            content = "用户偏好先给结论",
            kind = MemoryKind.OBSERVATION.name,
            state = MemoryState.ACTIVE.name,
            dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION,
            source = MemorySource.AUTO.name,
            canonicalKey = "shared-claim",
        )
        val firstProfile = MemoryEntity(
            id = 20,
            assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
            content = "画像一",
            kind = MemoryKind.PROFILE.name,
            dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION,
            source = MemorySource.AUTO.name,
            supportingObservationIds = JsonInstant.encodeToString(listOf(19)),
        )
        val secondProfile = firstProfile.copy(id = 23, content = "画像二")
        val pendingSibling = firstProfile.copy(
            id = 24,
            content = "待确认画像",
            state = MemoryState.PENDING.name,
        )
        val dao = FakeMemoryDao(observation, firstProfile, secondProfile, pendingSibling)
        val repository = MemoryRepository(dao)

        repository.deleteMemory(firstProfile.id)
        val firstObservationTombstone = requireNotNull(dao.getMemoryById(observation.id))
        val archivedSibling = dao.getMemoryById(secondProfile.id)
        val archivedPendingSibling = dao.getMemoryById(pendingSibling.id)

        assertEquals(MemoryState.DELETED.name, dao.getMemoryById(firstProfile.id)?.state)
        assertEquals(MemoryState.DELETED.name, firstObservationTombstone.state)
        assertEquals(MemoryState.ARCHIVED.name, archivedSibling?.state)
        assertEquals("画像二", archivedSibling?.content)
        assertEquals(MemoryState.ARCHIVED.name, archivedPendingSibling?.state)
        assertEquals("待确认画像", archivedPendingSibling?.content)
        assertTrue(repository.getPromptMemories("assistant-a").isEmpty())

        repository.deleteMemory(secondProfile.id)

        assertEquals(
            "shared-claim".memorySuppressionKey(),
            firstObservationTombstone.canonicalKey,
        )
        assertEquals(firstObservationTombstone, dao.getMemoryById(observation.id))
        assertEquals(MemoryState.DELETED.name, dao.getMemoryById(secondProfile.id)?.state)
    }

    @Test
    fun deletingConversationRevokesQuotesAndArchivesDerivedAutomaticMemory() = runBlocking {
        val evidence = listOf(
            ProfileEvidence("c1", "m1", "第一条", 10),
            ProfileEvidence("c2", "m2", "第二条", 20),
        )
        val observation = MemoryEntity(
            id = 21,
            assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
            content = "自动观察",
            kind = MemoryKind.OBSERVATION.name,
            state = MemoryState.ACTIVE.name,
            dimensionId = ProfileDimensions.PREFERENCES_VALUES,
            source = MemorySource.AUTO.name,
            profileEvidenceJson = JsonInstant.encodeToString(evidence),
            evidenceConversationIds = JsonInstant.encodeToString(listOf("c1", "c2")),
        )
        val profile = MemoryEntity(
            id = 22,
            assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
            content = "自动画像",
            kind = MemoryKind.PROFILE.name,
            state = MemoryState.ACTIVE.name,
            dimensionId = ProfileDimensions.PREFERENCES_VALUES,
            source = MemorySource.AUTO.name,
            profileEvidenceJson = JsonInstant.encodeToString(evidence),
            evidenceConversationIds = JsonInstant.encodeToString(listOf("c1", "c2")),
            supportingObservationIds = JsonInstant.encodeToString(listOf(21)),
        )
        val dao = FakeMemoryDao(observation, profile)
        val repository = MemoryRepository(dao)

        repository.revokeConversationEvidence("c1")

        val updatedObservation = requireNotNull(dao.getMemoryById(21)).toAssistantMemory()
        val updatedProfile = requireNotNull(dao.getMemoryById(22)).toAssistantMemory()
        assertEquals(MemoryState.ARCHIVED, updatedObservation.state)
        assertEquals(MemoryState.ARCHIVED, updatedProfile.state)
        assertEquals(listOf("m2"), updatedObservation.profileEvidence.map(ProfileEvidence::messageId))
        assertEquals(listOf("m2"), updatedProfile.profileEvidence.map(ProfileEvidence::messageId))
        assertFalse(updatedProfile.evidenceConversationIds.contains("c1"))
    }

    private suspend fun assertToolScopeNotFound(id: Int, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(
            "Memory record #$id not found in current memory scope",
            failure?.message,
        )
    }
}

private class FakeMemoryDao(vararg memories: MemoryEntity) : MemoryDAO {
    private val records = linkedMapOf<Int, MemoryEntity>().apply {
        memories.forEach { put(it.id, it) }
    }

    override fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> =
        flowOf(records.values.filter { it.assistantId == assistantId && it.state == MemoryState.ACTIVE.name })

    override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> =
        records.values.filter { it.assistantId == assistantId && it.state == MemoryState.ACTIVE.name }

    override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> =
        flowOf(records.values.filter { it.state == MemoryState.ACTIVE.name })

    override suspend fun getAllMemories(): List<MemoryEntity> =
        records.values.filter { it.state == MemoryState.ACTIVE.name }

    override fun getAllMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> =
        flowOf(records.values.filter { it.assistantId == assistantId })

    override suspend fun getAllMemoriesOfAssistant(assistantId: String): List<MemoryEntity> =
        records.values.filter { it.assistantId == assistantId }

    override suspend fun getActiveMemoriesOfKind(
        assistantId: String,
        kind: String,
        limit: Int,
    ): List<MemoryEntity> = records.values
        .filter {
            it.assistantId == assistantId &&
                it.kind == kind &&
                it.state == MemoryState.ACTIVE.name
        }
        .take(limit)

    override suspend fun getMemoryById(id: Int): MemoryEntity? = records[id]

    override suspend fun getMemoriesByIds(ids: List<Int>): List<MemoryEntity> =
        ids.mapNotNull(records::get)

    override suspend fun insertMemory(memory: MemoryEntity): Long {
        val id = memory.id.takeIf { it != 0 } ?: ((records.keys.maxOrNull() ?: 0) + 1)
        records[id] = memory.copy(id = id)
        return id.toLong()
    }

    override suspend fun updateMemory(memory: MemoryEntity) {
        records[memory.id] = memory
    }

    override suspend fun updateMemories(memories: List<MemoryEntity>) {
        memories.forEach { records[it.id] = it }
    }

    override suspend fun deleteMemory(id: Int) {
        records.remove(id)
    }

    override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        records.entries.removeAll { it.value.assistantId == assistantId }
    }
}
