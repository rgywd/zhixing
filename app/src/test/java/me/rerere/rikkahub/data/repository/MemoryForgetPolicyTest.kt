package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemoryState
import me.rerere.rikkahub.data.model.ProfileEvidence
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryForgetPolicyTest {
    @Test
    fun deletionTombstoneScrubsPersonalDataAndKeepsAutoObservationSuppressionKey() {
        val memory = observation(
            evidence = listOf(evidence("c1", "m1", "用户原话", 10)),
        ).copy(
            id = 7,
            content = "用户偏好先给结论",
            canonicalKey = "用户偏好先给结论",
            supportingObservationIds = JsonInstant.encodeToString(listOf(2, 3)),
        )

        val deleted = memory.asDeletionTombstone(now = 99)

        assertEquals(MemoryState.DELETED.name, deleted.state)
        assertEquals("", deleted.content)
        assertEquals("[]", deleted.evidenceConversationIds)
        assertEquals("[]", deleted.profileEvidenceJson)
        assertEquals("[]", deleted.supportingObservationIds)
        assertEquals("用户偏好先给结论".memorySuppressionKey(), deleted.canonicalKey)
        assertTrue(!deleted.canonicalKey.contains("用户"))
        assertEquals(0f, deleted.confidence)
        assertEquals(0L, deleted.firstEvidenceAt)
        assertEquals(0L, deleted.lastEvidenceAt)
        assertEquals(99L, deleted.updatedAt)
        assertTrue(deleted.locked)
    }

    @Test
    fun deletionTombstoneDoesNotKeepManualContextKey() {
        val deleted = MemoryEntity(
            id = 8,
            assistantId = "assistant-a",
            content = "明天下午三点开会",
            kind = MemoryKind.CONTEXT.name,
            source = MemorySource.MANUAL.name,
            canonicalKey = "temporary-key",
        ).asDeletionTombstone(now = 100)

        assertEquals("", deleted.canonicalKey)
    }

    @Test
    fun deletionTombstoneIsIdempotentForSharedAutomaticObservation() {
        val deleted = observation(
            evidence = listOf(evidence("c1", "m1", "用户原话", 10)),
        ).copy(
            id = 10,
            canonicalKey = "shared-claim",
        ).asDeletionTombstone(now = 100)

        val deletedAgain = deleted.asDeletionTombstone(now = 200)

        assertEquals(deleted, deletedAgain)
        assertEquals("shared-claim".memorySuppressionKey(), deletedAgain.canonicalKey)
    }

    @Test
    fun deletingProfileAlsoTombstonesOnlyItsAutomaticSupportingObservations() {
        val profile = MemoryEntity(
            id = 1,
            assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
            content = "自动画像",
            kind = MemoryKind.PROFILE.name,
            source = MemorySource.AUTO.name,
            supportingObservationIds = JsonInstant.encodeToString(listOf(2, 3)),
        )
        val supported = observation(
            evidence = listOf(evidence("c1", "m1", "第一条", 10))
        ).copy(id = 2, canonicalKey = "claim-2")
        val unrelatedContext = MemoryEntity(
            id = 3,
            assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
            kind = MemoryKind.CONTEXT.name,
            source = MemorySource.MANUAL.name,
            content = "不能被级联删除",
        )

        val tombstones = deletionTombstones(
            target = profile,
            supportingRecords = listOf(supported, unrelatedContext),
            now = 99,
        )

        assertEquals(listOf(1, 2), tombstones.map(MemoryEntity::id))
        assertTrue(tombstones.all { it.state == MemoryState.DELETED.name })
        assertEquals(
            "claim-2".memorySuppressionKey(),
            tombstones.single { it.id == 2 }.canonicalKey,
        )
    }

    @Test
    fun revokingConversationEvidenceArchivesAutomaticObservationAndKeepsOtherQuotes() {
        val memory = observation(
            evidence = listOf(
                evidence("c1", "m1", "第一条", 10),
                evidence("c2", "m2", "第二条", 20),
            ),
        )

        val updated = memory.withoutConversationEvidence("c1", now = 50)

        requireNotNull(updated)
        assertEquals(MemoryState.ARCHIVED.name, updated.state)
        assertEquals(JsonInstant.encodeToString(listOf("c2")), updated.evidenceConversationIds)
        assertEquals(
            listOf("m2"),
            JsonInstant.decodeFromString<List<ProfileEvidence>>(updated.profileEvidenceJson)
                .map(ProfileEvidence::messageId),
        )
        assertEquals(20L, updated.firstEvidenceAt)
        assertEquals(20L, updated.lastEvidenceAt)
        assertEquals(50L, updated.updatedAt)
    }

    @Test
    fun revokingConversationEvidenceKeepsConfirmedProfileContentAndState() {
        val memory = MemoryEntity(
            id = 9,
            assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
            content = "用户确认过的偏好",
            kind = MemoryKind.PROFILE.name,
            state = MemoryState.ACTIVE.name,
            source = MemorySource.MANUAL.name,
            locked = true,
            evidenceConversationIds = JsonInstant.encodeToString(listOf("c1", "c2")),
            profileEvidenceJson = JsonInstant.encodeToString(
                listOf(
                    evidence("c1", "m1", "第一条", 10),
                    evidence("c2", "m2", "第二条", 20),
                )
            ),
        )

        val updated = memory.withoutConversationEvidence("c1", now = 50)

        requireNotNull(updated)
        assertEquals("用户确认过的偏好", updated.content)
        assertEquals(MemoryState.ACTIVE.name, updated.state)
        assertEquals(JsonInstant.encodeToString(listOf("c2")), updated.evidenceConversationIds)
    }

    @Test
    fun unrelatedConversationDoesNotRewriteMemory() {
        val memory = observation(
            evidence = listOf(evidence("c2", "m2", "第二条", 20)),
        )

        assertNull(memory.withoutConversationEvidence("c1", now = 50))
    }

    private fun observation(evidence: List<ProfileEvidence>) = MemoryEntity(
        assistantId = MemoryRepository.GLOBAL_MEMORY_ID,
        kind = MemoryKind.OBSERVATION.name,
        state = MemoryState.ACTIVE.name,
        source = MemorySource.AUTO.name,
        confidence = 1f,
        evidenceConversationIds = JsonInstant.encodeToString(
            evidence.map(ProfileEvidence::conversationId).distinct()
        ),
        profileEvidenceJson = JsonInstant.encodeToString(evidence),
        firstEvidenceAt = evidence.minOfOrNull(ProfileEvidence::observedAt) ?: 0,
        lastEvidenceAt = evidence.maxOfOrNull(ProfileEvidence::observedAt) ?: 0,
    )

    private fun evidence(
        conversationId: String,
        messageId: String,
        quote: String,
        observedAt: Long,
    ) = ProfileEvidence(
        conversationId = conversationId,
        messageId = messageId,
        quote = quote,
        observedAt = observedAt,
    )
}
