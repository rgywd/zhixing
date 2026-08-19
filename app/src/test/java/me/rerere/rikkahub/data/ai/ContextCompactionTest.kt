package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactionTest {
    @Test
    fun `500k context policy prepares activates and protects at stable thresholds`() {
        val policy = contextCompactionPolicy(500_000)

        assertEquals(300_000, policy.prepareAtTokens)
        assertEquals(390_000, policy.activateAtTokens)
        assertEquals(436_000, policy.maximumPromptTokens)
        assertEquals(225_000, policy.targetPromptTokens)
        assertTrue(!policy.shouldPrepare(299_999))
        assertTrue(policy.shouldPrepare(300_000))
        assertTrue(!policy.shouldActivate(389_999))
        assertTrue(policy.shouldActivate(390_000))
        assertTrue(!policy.requiresSynchronousFallback(435_999))
        assertTrue(policy.requiresSynchronousFallback(436_000))
    }

    @Test
    fun `manual compaction keeps the latest complete turn and preserves original messages`() {
        val messages = listOf(
            UIMessage.user("old question"),
            UIMessage.assistant("old answer"),
            UIMessage.user("current question"),
            UIMessage.assistant("current answer"),
        )

        val plan = buildContextCompactionPlan(
            messages = messages,
            recentTokenBudget = 100,
            forceCompaction = true,
        )

        assertNotNull(plan)
        assertEquals(messages.take(2), plan!!.messagesToCompress)
        assertEquals(messages.takeLast(2), plan.messagesToKeep)

        val updated = applyContextCheckpoint(
            messages = messages,
            plan = plan,
            summary = "checkpoint summary",
            sourceTokenEstimate = 12_345,
            trigger = ContextCompactionTrigger.MANUAL,
            createdAtEpochMillis = 42L,
            active = true,
        )

        assertEquals(messages.map(UIMessage::id), updated.map(UIMessage::id))
        assertEquals(messages.map(UIMessage::toText), updated.map(UIMessage::toText))
        val annotation = updated[1].annotations
            .filterIsInstance<UIMessageAnnotation.ContextCheckpoint>()
            .single()
        assertEquals("checkpoint summary", annotation.summary)
        assertEquals(12_345, annotation.sourceTokenEstimate)

        val projection = updated.projectContextForPrompt()
        assertEquals("checkpoint summary", projection.checkpointSummary)
        assertEquals(messages.takeLast(2).map(UIMessage::id), projection.messages.map(UIMessage::id))
    }

    @Test
    fun `prepared automatic checkpoint stays out of prompt until activation`() {
        val messages = listOf(
            UIMessage.user("old question"),
            UIMessage.assistant("old answer"),
            UIMessage.user("current question"),
            UIMessage.assistant("current answer"),
        )
        val plan = buildContextCompactionPlan(
            messages = messages,
            recentTokenBudget = 100,
            forceCompaction = true,
        )!!
        val prepared = applyContextCheckpoint(
            messages = messages,
            plan = plan,
            summary = "prepared summary",
            sourceTokenEstimate = 300_000,
            trigger = ContextCompactionTrigger.AUTO,
            createdAtEpochMillis = 42L,
            active = false,
        )

        assertTrue(prepared.hasPreparedContextCheckpoint())
        assertNull(prepared.projectContextForPrompt().checkpointSummary)
        assertEquals(messages.map(UIMessage::id), prepared.projectContextForPrompt().messages.map(UIMessage::id))

        val activated = prepared.activateLatestPreparedContextCheckpoint()

        assertTrue(!activated.hasPreparedContextCheckpoint())
        assertEquals("prepared summary", activated.projectContextForPrompt().checkpointSummary)
        assertEquals(messages.takeLast(2).map(UIMessage::id), activated.projectContextForPrompt().messages.map(UIMessage::id))
    }

    @Test
    fun `automatic history budget targets total prompt instead of fixed message count`() {
        val budget = automaticRecentTokenBudget(
            contextWindowTokens = 500_000,
            sourcePromptTokens = 300_000,
            projectedHistoryTokens = 180_000,
        )

        assertEquals(97_000, budget)
    }

    @Test
    fun `automatic compaction uses token budget instead of message count`() {
        val messages = listOf(
            UIMessage.user("a".repeat(600)),
            UIMessage.assistant("b".repeat(600)),
            UIMessage.user("small current question"),
            UIMessage.assistant("small current answer"),
        )

        val plan = buildContextCompactionPlan(
            messages = messages,
            recentTokenBudget = 100,
            forceCompaction = false,
        )

        assertNotNull(plan)
        assertEquals(messages.take(2), plan!!.messagesToCompress)
        assertEquals(messages.takeLast(2), plan.messagesToKeep)
    }

    @Test
    fun `a single latest turn is never compacted as historical context`() {
        val messages = listOf(
            UIMessage.user("one large current request".repeat(1_000)),
            UIMessage.assistant("current response"),
        )

        assertNull(
            buildContextCompactionPlan(
                messages = messages,
                recentTokenBudget = 100,
                forceCompaction = true,
            )
        )
    }

    @Test
    fun `new checkpoint folds the previous checkpoint and only advances over new completed history`() {
        val original = listOf(
            UIMessage.user("first question"),
            UIMessage.assistant("first answer"),
            UIMessage.user("second question"),
            UIMessage.assistant("second answer"),
        )
        val firstPlan = buildContextCompactionPlan(
            messages = original,
            recentTokenBudget = 100,
            forceCompaction = true,
        )!!
        val withCheckpoint = applyContextCheckpoint(
            messages = original,
            plan = firstPlan,
            summary = "first checkpoint",
            sourceTokenEstimate = 1_000,
            trigger = ContextCompactionTrigger.MANUAL,
            createdAtEpochMillis = 1L,
        )
        val extended = withCheckpoint + listOf(
            UIMessage.user("third question"),
            UIMessage.assistant("third answer"),
        )

        val nextPlan = buildContextCompactionPlan(
            messages = extended,
            recentTokenBudget = 100,
            forceCompaction = true,
        )

        assertNotNull(nextPlan)
        assertEquals("first checkpoint", nextPlan!!.priorCheckpointSummary)
        assertEquals(
            listOf("second question", "second answer"),
            nextPlan.messagesToCompress.map(UIMessage::toText),
        )
        assertTrue(nextPlan.boundaryIndex > firstPlan.boundaryIndex)
    }

    @Test
    fun `compaction input is split by token budget rather than message count`() {
        val content = buildString {
            repeat(400) { index ->
                appendLine("line-$index ${"内容".repeat(20)}")
            }
        }

        val chunks = splitCompactionContent(content, tokenBudget = 200)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { estimateTextTokens(it) <= 200 })
        assertEquals(
            content.filterNot(Char::isWhitespace),
            chunks.joinToString("").filterNot(Char::isWhitespace),
        )
    }

    @Test
    fun `editing invalidation removes every checkpoint annotation`() {
        val messages = listOf(
            UIMessage.user("old").copy(
                annotations = listOf(
                    UIMessageAnnotation.ContextCheckpoint(summary = "checkpoint")
                )
            ),
            UIMessage.assistant("recent"),
        )

        val cleared = messages.clearContextCheckpoints()

        assertTrue(cleared.all { message ->
            message.annotations.none { it is UIMessageAnnotation.ContextCheckpoint }
        })
        assertEquals(messages.map(UIMessage::id), cleared.map(UIMessage::id))
    }
}
