package me.rerere.rikkahub.data.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.ProfileDimensions
import me.rerere.rikkahub.data.repository.ProfileMemoryMutationGate
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
    fun `maintenance gate permits nested repository mutation scope`() = runBlocking {
        val events = mutableListOf<String>()

        ProfileMaintenanceRunGate.run {
            events += "maintenance"
            ProfileMemoryMutationGate.run {
                events += "repository"
            }
        }

        assertEquals(listOf("maintenance", "repository"), events)
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

    @Test
    fun profileBatchKeepsNewestUserTextWhenCharacterBudgetIsTight() {
        val selected = selectRecentProfileUserMessages(
            messages = listOf(
                UIMessage.user("older-message"),
                UIMessage.assistant("assistant-message"),
                UIMessage.user("latest"),
            ),
            charBudget = 6,
        )

        assertEquals(listOf("latest"), selected.map(SelectedProfileUserMessage::text))
    }

    @Test
    fun profileBatchRestoresChronologicalOrderAfterNewestFirstSelection() {
        val selected = selectRecentProfileUserMessages(
            messages = listOf(
                UIMessage.user("oldest"),
                UIMessage.user("middle"),
                UIMessage.user("latest"),
            ),
            charBudget = 12,
        )

        assertEquals(
            listOf("middle", "latest"),
            selected.map(SelectedProfileUserMessage::text),
        )
    }

    @Test
    fun profileEvidenceTextIsAnExactRawUserTextSubstring() {
        val selected = selectRecentProfileUserMessages(
            messages = listOf(UIMessage.user("abcdef")),
            charBudget = 4,
        )

        assertEquals(listOf("abcd"), selected.map(SelectedProfileUserMessage::text))
        assertFalse(selected.single().text.contains("[USER]"))
        assertFalse(selected.single().text.endsWith("..."))
    }
}
