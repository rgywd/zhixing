package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MemoryDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerSecurityTest {
    @Test
    fun `legacy approval tools execute while ask user remains waiting for an answer`() {
        var legacyExecuteCalled = false
        val legacyTool = Tool(
            name = "legacy_write",
            description = "",
            needsApproval = { true },
            execute = {
                legacyExecuteCalled = true
                emptyList()
            },
        )
        val askUserTool = Tool(
            name = "ask_user",
            description = "",
            requiresUserAnswer = true,
            execute = { error("ask_user execute must not be called") },
        )
        val prepared = prepareToolsForUserAnswer(
            tools = listOf(
                UIMessagePart.Tool("legacy", legacyTool.name, "{}"),
                UIMessagePart.Tool("question", askUserTool.name, "{}"),
            ),
            definitions = listOf(legacyTool, askUserTool),
        )

        assertFalse(prepared.tools.single { it.toolName == legacyTool.name }.isPending)
        assertTrue(prepared.tools.single { it.toolName == askUserTool.name }.isPending)
        assertTrue(prepared.isWaitingForUserAnswer)
        assertFalse(legacyExecuteCalled)

        val resumed = prepareToolsForUserAnswer(
            tools = listOf(
                UIMessagePart.Tool(
                    toolCallId = "question",
                    toolName = askUserTool.name,
                    input = "{}",
                    approvalState = ToolApprovalState.Answered("{\"answers\":{\"q1\":\"yes\"}}"),
                ),
            ),
            definitions = listOf(askUserTool),
        )
        assertFalse(resumed.isWaitingForUserAnswer)
        assertTrue(resumed.tools.single().approvalState is ToolApprovalState.Answered)
    }

    @Test
    fun `legacy pending ordinary tool is normalized for execution`() {
        val tool = Tool(
            name = "legacy_write",
            description = "",
            needsApproval = { true },
            execute = { emptyList() },
        )

        val prepared = prepareToolsForUserAnswer(
            tools = listOf(
                UIMessagePart.Tool(
                    toolCallId = "legacy",
                    toolName = tool.name,
                    input = "{}",
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
            definitions = listOf(tool),
        )

        assertFalse(prepared.isWaitingForUserAnswer)
        assertTrue(prepared.tools.single().approvalState is ToolApprovalState.Auto)
    }

    @Test
    fun toolExecutionLogContainsOnlyToolIdentity() {
        val message = toolExecutionLogMessage("memory_tool")

        assertEquals("generateText: executing tool memory_tool", message)
        assertFalse(message.contains("content"))
        assertFalse(message.contains("args"))
    }

    @Test
    fun readOnlyBatchRunsConcurrentlyAndSerialBoundaryKeepsOrder() = runBlocking {
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var started = 0
        val execution = async {
            executeInOrderedBatches(
                items = listOf("read-1", "read-2", "write", "read-3"),
                canRunInParallel = { it.startsWith("read") },
                execute = { item ->
                    if (item == "read-1" || item == "read-2") {
                        started++
                        if (started == 2) bothStarted.complete(Unit)
                        release.await()
                    }
                    item
                },
            )
        }

        withTimeout(1_000) { bothStarted.await() }
        release.complete(Unit)
        assertEquals(listOf("read-1", "read-2", "write", "read-3"), execution.await())
    }

    @Test
    fun memoryPromptSnapshotRefreshesAfterSuccessfulToolMutation() = runBlocking {
        val initial = listOf(MemoryDocument("scope", "/profile.md", "Profile", "profile", content = "old"))
        val refreshed = listOf(MemoryDocument("scope", "/profile.md", "Profile", "profile", content = "new"))
        val snapshot = MemoryDocumentPromptSnapshot(initial)
        var refreshCount = 0

        assertEquals(initial, snapshot.resolve { error("must not refresh an unchanged snapshot") })

        snapshot.invalidate()
        assertEquals(
            refreshed,
            snapshot.resolve {
                refreshCount += 1
                refreshed
            },
        )
        assertEquals(
            refreshed,
            snapshot.resolve {
                refreshCount += 1
                error("must not refresh the same snapshot twice")
            },
        )
        assertEquals(1, refreshCount)
    }

    @Test
    fun toolInputIsSanitizedBeforeItCanBePersisted() {
        val tool = Tool(
            name = "sensitive_tool",
            description = "",
            sanitizeInputForStorage = { """{"safe_summary":"kept"}""" },
            execute = { emptyList() },
        )
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = tool.name,
                        input = """{"raw_text":"must not persist"}""",
                    ),
                ),
            ),
        )

        val sanitized = sanitizeToolInputsForStorage(messages, listOf(tool))
            .single()
            .parts
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
            .input

        assertEquals("""{"safe_summary":"kept"}""", sanitized)
        assertFalse(sanitized.contains("must not persist"))
    }

    @Test
    fun failingToolInputSanitizerFailsClosed() {
        val tool = Tool(
            name = "sensitive_tool",
            description = "",
            sanitizeInputForStorage = { error("sanitizer failure") },
            execute = { emptyList() },
        )
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = tool.name,
                        input = """{"raw_text":"must not persist"}""",
                    ),
                ),
            ),
        )

        val sanitized = sanitizeToolInputsForStorage(messages, listOf(tool))
            .single()
            .parts
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
            .input

        assertEquals("{}", sanitized)
        assertTrue(sanitized.length <= 2)
    }
}
