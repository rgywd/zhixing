package me.rerere.rikkahub.data.profile

import me.rerere.rikkahub.data.datastore.ProfileMaintenanceConfig
import me.rerere.rikkahub.data.datastore.ProfileMaintenanceStrategy
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemoryState
import me.rerere.rikkahub.data.model.ProfileDimensions
import me.rerere.rikkahub.data.model.ProfileEvidence
import me.rerere.rikkahub.data.repository.memorySuppressionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProfileMemoryPolicyTest {
    @Test
    fun `duplicate automatic summaries keep one strongest profile per dimension`() {
        val profiles = listOf(
            profile(id = 34, dimensionId = ProfileDimensions.IDENTITY_CONTEXT, evidenceCount = 4),
            profile(id = 35, dimensionId = ProfileDimensions.PREFERENCES_VALUES, evidenceCount = 4),
            profile(id = 36, dimensionId = ProfileDimensions.CAPABILITIES_KNOWLEDGE, evidenceCount = 4),
            profile(id = 37, dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION, evidenceCount = 4),
            profile(id = 38, dimensionId = ProfileDimensions.IDENTITY_CONTEXT, evidenceCount = 6),
            profile(id = 39, dimensionId = ProfileDimensions.PREFERENCES_VALUES, evidenceCount = 6),
            profile(id = 40, dimensionId = ProfileDimensions.CAPABILITIES_KNOWLEDGE, evidenceCount = 6),
            profile(id = 41, dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION, evidenceCount = 6),
            profile(id = 42, dimensionId = ProfileDimensions.PREFERENCES_VALUES, evidenceCount = 8),
            profile(id = 43, dimensionId = ProfileDimensions.IDENTITY_CONTEXT, evidenceCount = 8),
            profile(id = 44, dimensionId = ProfileDimensions.CAPABILITIES_KNOWLEDGE, evidenceCount = 8),
            profile(id = 45, dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION, evidenceCount = 8),
            profile(
                id = 46,
                dimensionId = ProfileDimensions.IDENTITY_CONTEXT,
                evidenceCount = 10,
                locked = true,
            ),
            profile(
                id = 47,
                dimensionId = ProfileDimensions.IDENTITY_CONTEXT,
                evidenceCount = 10,
                state = MemoryState.ARCHIVED,
            ),
        )

        assertEquals(
            setOf(34, 35, 36, 37, 38, 39, 40, 41),
            duplicateAutoProfileIdsToArchive(profiles),
        )
    }

    @Test
    fun `observation evidence must quote an allowed user message`() {
        val source = ProfileEvidenceSource("c1", "u1", "我长期更喜欢先讨论方案，再开始写代码。", 1L)
        val candidate = candidate(
            evidence = listOf(ProfileEvidenceReference("c1", "u1", "先讨论方案，再开始写代码")),
        )

        assertNotNull(validateProfileObservation(candidate, mapOf("u1" to source), emptySet()))
        assertNull(
            validateProfileObservation(
                candidate.copy(evidence = listOf(ProfileEvidenceReference("c1", "assistant-1", "先讨论方案"))),
                mapOf("u1" to source),
                emptySet(),
            )
        )
        assertNull(
            validateProfileObservation(
                candidate.copy(evidence = listOf(ProfileEvidenceReference("c1", "u1", "用户从未说过的话"))),
                mapOf("u1" to source),
                emptySet(),
            )
        )
    }

    @Test
    fun `credential-like material is rejected even when model marks it safe`() {
        val source = ProfileEvidenceSource("c1", "u1", "我的 API key 是 abc123", 1L)
        val candidate = candidate(
            evidence = listOf(ProfileEvidenceReference("c1", "u1", "我的 API key 是 abc123")),
        ).copy(content = "用户的 API key 是 abc123", sensitive = false)

        assertNull(validateProfileObservation(candidate, mapOf("u1" to source), emptySet()))
    }

    @Test
    fun `model durability cannot bypass longitudinal promotion gates`() {
        val config = ProfileMaintenanceConfig(minimumEvidence = 3, minimumEvidenceSpanDays = 7)
        val oneConversation = listOf(evidence("c1", "m1", day = 0))
        val sameDay = listOf(
            evidence("c1", "m1", day = 0),
            evidence("c2", "m2", day = 0),
            evidence("c3", "m3", day = 0),
        )
        val longitudinal = listOf(
            evidence("c1", "m1", day = 0),
            evidence("c2", "m2", day = 4),
            evidence("c3", "m3", day = 8),
        )

        assertFalse(qualifiesForProfile(oneConversation, config))
        assertFalse(qualifiesForProfile(sameDay, config))
        assertTrue(qualifiesForProfile(longitudinal, config))
        assertEquals(1f, observationConfidence(longitudinal, config), 0.0001f)
    }

    @Test
    fun `strategy threshold is applied to deterministic evidence diversity`() {
        val twoDistinctDays = listOf(
            evidence("c1", "m1", day = 0),
            evidence("c2", "m2", day = 0),
            evidence("c3", "m3", day = 8),
        )
        val balanced = ProfileMaintenanceConfig(
            strategy = ProfileMaintenanceStrategy.BALANCED,
            minimumEvidence = 3,
            minimumEvidenceSpanDays = 7,
        )
        val conservative = balanced.copy(strategy = ProfileMaintenanceStrategy.CONSERVATIVE)

        assertTrue(qualifiesForProfile(twoDistinctDays, balanced))
        assertFalse(qualifiesForProfile(twoDistinctDays, conservative))
    }

    @Test
    fun `evidence accumulates by unique user message`() {
        val merged = mergeProfileEvidence(
            current = listOf(evidence("c1", "m1", day = 0)),
            incoming = listOf(
                evidence("c1", "m1", day = 0),
                evidence("c2", "m2", day = 3),
            ),
        )

        assertEquals(listOf("m1", "m2"), merged.map(ProfileEvidence::messageId))
    }

    @Test
    fun `summary can only cite qualified observations from one dimension`() {
        val qualified = mapOf(
            7 to AssistantMemory(
                id = 7,
                content = "用户长期偏好先讨论方案。",
                kind = MemoryKind.OBSERVATION,
                dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION,
            )
        )
        val valid = ProfileSummaryCandidate(
            dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION,
            content = "用户倾向于先对齐方案和风险，再进入实现阶段。",
            observationIds = listOf(7),
        )

        assertNotNull(validateProfileSummary(valid, qualified))
        assertNull(validateProfileSummary(valid.copy(observationIds = listOf(7, 8)), qualified))
        assertNull(validateProfileSummary(valid.copy(dimensionId = ProfileDimensions.IDENTITY_CONTEXT), qualified))
    }

    @Test
    fun `staleness uses last supporting evidence time`() {
        val now = TimeUnit.DAYS.toMillis(200)

        assertTrue(isObservationStale(TimeUnit.DAYS.toMillis(10), now, staleAfterDays = 180))
        assertFalse(isObservationStale(TimeUnit.DAYS.toMillis(30), now, staleAfterDays = 180))
    }

    @Test
    fun `deleted observation suppresses the same automatic claim but not its whole dimension`() {
        val deleted = AssistantMemory(
            id = 21,
            content = "",
            kind = MemoryKind.OBSERVATION,
            state = MemoryState.DELETED,
            dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION,
            source = MemorySource.AUTO,
            canonicalKey = "用户偏好先给结论".memorySuppressionKey(),
            locked = true,
        )
        val sameClaim = ValidatedProfileObservation(
            action = "create",
            targetObservationId = null,
            dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION,
            content = "用户偏好先给结论",
            canonicalKey = "用户偏好先给结论",
            evidence = emptyList(),
        )

        assertTrue(isSuppressedObservationCandidate(sameClaim, listOf(deleted)))
        assertTrue(
            isSuppressedObservationCandidate(
                sameClaim.copy(action = "reinforce", targetObservationId = 21),
                listOf(deleted),
            )
        )
        assertFalse(
            isSuppressedObservationCandidate(
                sameClaim.copy(
                    content = "用户偏好查看测试证据",
                    canonicalKey = "用户偏好查看测试证据",
                ),
                listOf(deleted),
            )
        )
    }

    @Test
    fun `observation quote cannot span separate user text parts`() {
        val source = ProfileEvidenceSource(
            conversationId = "c1",
            messageId = "u1",
            text = "先讨论方案\n再开始写代码",
            observedAt = 1L,
            quoteSegments = listOf("先讨论方案", "再开始写代码"),
        )
        val candidate = candidate(
            evidence = listOf(ProfileEvidenceReference("c1", "u1", "方案\n再开始")),
        )

        assertNull(validateProfileObservation(candidate, mapOf("u1" to source), emptySet()))
    }

    @Test
    fun `canonical profile requires every recorded supporting observation to stay qualified`() {
        val canonical = profile(
            id = 30,
            dimensionId = ProfileDimensions.PREFERENCES_VALUES,
            evidenceCount = 3,
        ).copy(supportingObservationIds = listOf(1, 2))
        val qualifiedOne = AssistantMemory(
            id = 1,
            kind = MemoryKind.OBSERVATION,
            state = MemoryState.ACTIVE,
            dimensionId = ProfileDimensions.PREFERENCES_VALUES,
        )
        val qualifiedTwo = qualifiedOne.copy(id = 2)

        assertFalse(hasQualifiedProfileSupport(canonical, listOf(qualifiedOne)))
        assertTrue(hasQualifiedProfileSupport(canonical, listOf(qualifiedOne, qualifiedTwo)))
    }

    @Test
    fun `legacy profile without support ids falls back to qualified dimension`() {
        val canonical = profile(
            id = 31,
            dimensionId = ProfileDimensions.IDENTITY_CONTEXT,
            evidenceCount = 3,
        )
        val qualified = AssistantMemory(
            id = 4,
            kind = MemoryKind.OBSERVATION,
            state = MemoryState.ACTIVE,
            dimensionId = ProfileDimensions.IDENTITY_CONTEXT,
        )

        assertTrue(hasQualifiedProfileSupport(canonical, listOf(qualified)))
    }

    private fun candidate(evidence: List<ProfileEvidenceReference>) = ProfileObservationCandidate(
        dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION,
        content = "用户长期倾向于先讨论方案再开始实现。",
        durable = true,
        evidence = evidence,
    )

    private fun evidence(conversationId: String, messageId: String, day: Long) = ProfileEvidence(
        conversationId = conversationId,
        messageId = messageId,
        quote = "用户原话-$messageId",
        observedAt = TimeUnit.DAYS.toMillis(day),
    )

    private fun profile(
        id: Int,
        dimensionId: String,
        evidenceCount: Int,
        locked: Boolean = false,
        state: MemoryState = MemoryState.ACTIVE,
    ) = AssistantMemory(
        id = id,
        content = "自动画像-$id",
        kind = MemoryKind.PROFILE,
        state = state,
        updatedAt = id.toLong(),
        dimensionId = dimensionId,
        source = MemorySource.AUTO,
        evidenceConversationIds = (1..evidenceCount).map { "c-$it" },
        locked = locked,
        lastEvidenceAt = id.toLong(),
    )
}
