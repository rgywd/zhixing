package me.rerere.rikkahub.data.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.ProfileDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMaintenanceServiceTest {
    @Test
    fun `maintenance run gate serializes workers from different scheduler entries`() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            ProfileMaintenanceRunGate.run {
                events += "first-started"
                releaseFirst.await()
                events += "first-finished"
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            ProfileMaintenanceRunGate.run {
                events += "second-started"
            }
        }

        assertEquals(listOf("first-started"), events)
        assertFalse(second.isCompleted)

        releaseFirst.complete(Unit)
        awaitAll(first, second)

        assertEquals(listOf("first-started", "first-finished", "second-started"), events)
        assertTrue(second.isCompleted)
    }

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
