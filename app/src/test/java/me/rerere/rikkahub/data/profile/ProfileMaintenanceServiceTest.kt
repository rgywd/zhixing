package me.rerere.rikkahub.data.profile

import me.rerere.rikkahub.data.datastore.ProfileMaintenanceConfig
import me.rerere.rikkahub.data.datastore.ProfileMaintenanceStrategy
import me.rerere.rikkahub.data.model.ProfileDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileMaintenanceServiceTest {
    @Test
    fun parsesJsonWrappedInMarkdown() {
        val parsed = parseProfileMaintenanceResponse(
            """
            Here is the result:
            ```json
            {"candidates":[{"action":"create","dimensionId":"behavior_collaboration","content":"用户偏好先给结论。","confidence":0.9,"explicit":true,"evidenceConversationIds":["c1"]}]}
            ```
            """.trimIndent()
        )

        assertEquals(1, parsed.candidates.size)
        assertEquals(ProfileDimensions.BEHAVIOR_COLLABORATION, parsed.candidates.single().dimensionId)
    }

    @Test
    fun inferredCandidateNeedsConfiguredDistinctEvidence() {
        val config = ProfileMaintenanceConfig(
            strategy = ProfileMaintenanceStrategy.BALANCED,
            minimumEvidence = 2,
        )
        val candidate = ProfileCandidate(
            dimensionId = ProfileDimensions.PREFERENCES_VALUES,
            content = "用户偏好复用成熟交互。",
            confidence = 0.88f,
            explicit = false,
            evidenceConversationIds = listOf("c1", "c1", "unknown"),
        )

        assertNull(validateProfileCandidate(candidate, config, setOf("c1", "c2")))
        assertNotNull(
            validateProfileCandidate(
                candidate.copy(evidenceConversationIds = listOf("c1", "c2")),
                config,
                setOf("c1", "c2"),
            )
        )
    }

    @Test
    fun explicitCandidateCanUseOneEvidenceButStillMeetsConfidenceThreshold() {
        val config = ProfileMaintenanceConfig(
            strategy = ProfileMaintenanceStrategy.CONSERVATIVE,
            minimumEvidence = 3,
        )
        val candidate = ProfileCandidate(
            dimensionId = ProfileDimensions.IDENTITY_CONTEXT,
            content = "用户明确要求使用中文。",
            confidence = 0.91f,
            explicit = true,
            evidenceConversationIds = listOf("c1"),
        )

        assertNotNull(validateProfileCandidate(candidate, config, setOf("c1")))
        assertNull(validateProfileCandidate(candidate.copy(confidence = 0.89f), config, setOf("c1")))
    }

    @Test
    fun rejectsUnknownDimensionAndEvidenceOutsideBatch() {
        val config = ProfileMaintenanceConfig(minimumEvidence = 1)
        val base = ProfileCandidate(
            dimensionId = "future_dimension",
            content = "用户偏好中文回复。",
            confidence = 0.95f,
            explicit = true,
            evidenceConversationIds = listOf("c1"),
        )

        assertNull(validateProfileCandidate(base, config, setOf("c1")))
        assertNull(
            validateProfileCandidate(
                base.copy(dimensionId = ProfileDimensions.BEHAVIOR_COLLABORATION),
                config,
                setOf("c2"),
            )
        )
    }
}
