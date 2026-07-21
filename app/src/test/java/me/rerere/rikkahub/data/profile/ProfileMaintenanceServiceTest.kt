package me.rerere.rikkahub.data.profile

import me.rerere.rikkahub.data.model.ProfileDimensions
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileMaintenanceServiceTest {
    @Test
    fun parsesObservationJsonWrappedInMarkdown() {
        val parsed = parseProfileObservationResponse(
            """
            Here is the result:
            ```json
            {"observations":[{
              "action":"create",
              "dimensionId":"behavior_collaboration",
              "content":"用户长期偏好先给结论。",
              "durable":true,
              "sensitive":false,
              "evidence":[{"conversationId":"c1","messageId":"m1","quote":"先给结论"}]
            }]}
            ```
            """.trimIndent()
        )

        assertEquals(1, parsed.observations.size)
        assertEquals(
            ProfileDimensions.BEHAVIOR_COLLABORATION,
            parsed.observations.single().dimensionId,
        )
    }

    @Test
    fun parsesCanonicalSummaryResponse() {
        val parsed = parseProfileSummaryResponse(
            """
            {"summaries":[{"dimensionId":"preferences_values","content":"用户重视成熟方案与长期维护成本。","observationIds":[11,12]}]}
            """.trimIndent()
        )

        assertEquals(1, parsed.summaries.size)
        assertEquals(listOf(11, 12), parsed.summaries.single().observationIds)
    }
}
