package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `completion of replaced job cannot clear the current job`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirstCompletion = CompletableDeferred<Unit>()
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation(
                assistantId = Uuid.random(),
                messageNodes = emptyList(),
            ),
            scope = scope,
            onIdle = {},
        )
        val first = scope.launch {
            try {
                firstStarted.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    releaseFirstCompletion.await()
                }
            }
        }
        session.setJob(first)
        firstStarted.await()

        val second = scope.launch { awaitCancellation() }
        session.setJob(second)
        assertSame(second, session.getJob())

        releaseFirstCompletion.complete(Unit)
        first.join()
        assertSame(second, session.getJob())

        second.cancelAndJoin()
        scope.cancel()
    }
}
