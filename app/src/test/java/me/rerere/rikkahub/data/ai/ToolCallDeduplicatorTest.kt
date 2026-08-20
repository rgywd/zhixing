package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ToolCallDeduplicatorTest {
    @Test
    fun `same normalized read only call reuses success with a compact marker`() = runBlocking {
        val deduplicator = ToolCallDeduplicator()
        val executions = AtomicInteger()
        val largeOutput = "result".repeat(2_000)
        val firstArguments = buildJsonObject {
            put("query", "dots3-note preview")
            put("page", 1)
            put("purpose", "Find reviews")
        }
        val repeatedArguments = buildJsonObject {
            put("purpose", "Find domestic reviews")
            put("page", 1)
            put("query", "dots3-note preview")
        }

        val first = deduplicator.execute(
            toolCallId = "call-1",
            toolName = "search_web",
            arguments = firstArguments,
            enabled = true,
            ignoredArgumentFields = setOf("purpose"),
        ) {
            executions.incrementAndGet()
            listOf(UIMessagePart.Text(largeOutput))
        }
        val repeated = deduplicator.execute(
            toolCallId = "call-2",
            toolName = "search_web",
            arguments = repeatedArguments,
            enabled = true,
            ignoredArgumentFields = setOf("purpose"),
        ) {
            executions.incrementAndGet()
            error("duplicate call must not execute")
        }

        assertEquals(1, executions.get())
        assertEquals(largeOutput, (first.single() as UIMessagePart.Text).text)
        val marker = (repeated.single() as UIMessagePart.Text).text
        assertTrue(marker.contains("duplicate_skipped"))
        assertTrue(marker.contains("call-1"))
        assertFalse(marker.contains(largeOutput))
        assertTrue(marker.length < 300)
    }

    @Test
    fun `same batch concurrent duplicates execute only the first call`() = runBlocking {
        val deduplicator = ToolCallDeduplicator()
        val executions = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val arguments = buildJsonObject { put("query", "same") }

        val first = async {
            deduplicator.execute("call-1", "search_web", arguments, enabled = true) {
                executions.incrementAndGet()
                started.complete(Unit)
                release.await()
                listOf(UIMessagePart.Text("original"))
            }
        }
        started.await()
        val repeated = async {
            deduplicator.execute("call-2", "search_web", arguments, enabled = true) {
                executions.incrementAndGet()
                error("duplicate call must wait for and reuse the first success")
            }
        }
        release.complete(Unit)

        assertEquals("original", (first.await().single() as UIMessagePart.Text).text)
        assertTrue((repeated.await().single() as UIMessagePart.Text).text.contains("call-1"))
        assertEquals(1, executions.get())
    }

    @Test
    fun `failed original call does not block a later retry`() = runBlocking {
        val deduplicator = ToolCallDeduplicator()
        val executions = AtomicInteger()
        val arguments = buildJsonObject { put("query", "retry") }

        val failure = runCatching {
            deduplicator.execute("call-1", "search_web", arguments, enabled = true) {
                executions.incrementAndGet()
                error("temporary failure")
            }
        }
        val retry = deduplicator.execute("call-2", "search_web", arguments, enabled = true) {
            executions.incrementAndGet()
            listOf(UIMessagePart.Text("recovered"))
        }

        assertTrue(failure.isFailure)
        assertEquals("recovered", (retry.single() as UIMessagePart.Text).text)
        assertEquals(2, executions.get())
    }

    @Test
    fun `disabled differently named and materially different calls always execute`() = runBlocking {
        val deduplicator = ToolCallDeduplicator()
        val executions = AtomicInteger()
        val arguments = buildJsonObject { put("query", "same") }
        suspend fun execute(callId: String, toolName: String, enabled: Boolean) {
            deduplicator.execute(callId, toolName, arguments, enabled) {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("executed"))
            }
        }

        execute("write-1", "memory_write", enabled = false)
        execute("write-2", "memory_write", enabled = false)
        execute("web-1", "search_web", enabled = true)
        execute("image-1", "search_images", enabled = true)
        deduplicator.execute(
            "web-2",
            "search_web",
            buildJsonObject { put("query", "different") },
            enabled = true,
        ) {
            executions.incrementAndGet()
            listOf(UIMessagePart.Text("executed"))
        }

        assertEquals(5, executions.get())
    }
}
