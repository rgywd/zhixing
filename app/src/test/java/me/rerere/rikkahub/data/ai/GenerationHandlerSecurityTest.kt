package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.core.ToolExecutionException
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
    fun runScopedDeduplicationRequiresExplicitReadOnlyOptIn() {
        fun tool(
            executionMode: ToolExecutionMode,
            deduplicateWithinRun: Boolean,
        ) = Tool(
            name = "test",
            description = "",
            executionMode = executionMode,
            deduplicateWithinRun = deduplicateWithinRun,
            execute = { emptyList() },
        )

        assertTrue(tool(ToolExecutionMode.PARALLEL_READ_ONLY, true).isRunScopedDeduplicationEnabled())
        assertFalse(tool(ToolExecutionMode.PARALLEL_READ_ONLY, false).isRunScopedDeduplicationEnabled())
        assertFalse(tool(ToolExecutionMode.SERIAL, true).isRunScopedDeduplicationEnabled())
    }

    @Test
    fun wrappedToolExecutionExceptionKeepsItsStableErrorCode() {
        val wrapped = IllegalStateException(
            "provider wrapper",
            ToolExecutionException("MEMORY_SOURCE_INVALID"),
        )

        assertEquals("MEMORY_SOURCE_INVALID", toolExecutionErrorCode(wrapped))
        assertEquals("TOOL_EXECUTION_FAILED", toolExecutionErrorCode(IllegalStateException("unknown")))
    }

    @Test
    fun providerNativeSearchAliasesUseTheSearchFailureRecoveryPath() {
        listOf(
            "search_web",
            "search_images",
            "scrape_web",
            "web_search",
            "web_search_with_snippets",
            "x_search",
            "browse_page",
            "open_page",
        ).forEach { toolName ->
            assertTrue("Expected $toolName to be treated as search", isSearchLikeToolName(toolName))
        }

        assertFalse(isSearchLikeToolName("memory_read"))
        assertFalse(isSearchLikeToolName("workspace_shell"))
    }

    @Test
    fun searchFailuresTellTheModelToContinueWithoutUndeclaredProviderTools() {
        val searchFailure = toolExecutionFailureMessage("web_search", "TOOL_EXECUTION_FAILED")
        val ordinaryFailure = toolExecutionFailureMessage("memory_read", "TOOL_EXECUTION_FAILED")

        assertTrue(searchFailure.contains("Do not call provider-native or undeclared search tools"))
        assertTrue(searchFailure.contains("Continue without search"))
        assertEquals(
            "[TOOL_EXECUTION_FAILED] 工具执行失败，请检查连接、权限或输入后重试",
            ordinaryFailure,
        )
    }

    @Test
    fun failedSearchToolsAreDetectedFromStructuredToolOutput() {
        val json = Json { ignoreUnknownKeys = true }
        val failedSearch = UIMessagePart.Tool(
            toolCallId = "search-1",
            toolName = "web_search",
            input = "{}",
            output = listOf(UIMessagePart.Text("""{"error":"unavailable"}""")),
        )
        val successfulSearch = failedSearch.copy(
            output = listOf(UIMessagePart.Text("""{"items":[]}""")),
        )
        val failedMemory = failedSearch.copy(toolName = "memory_read")

        assertTrue(isFailedSearchTool(failedSearch, json))
        assertFalse(isFailedSearchTool(successfulSearch, json))
        assertFalse(isFailedSearchTool(failedMemory, json))
    }

    @Test
    fun onlyNewActionableProviderOutputSuppressesTheLocalSearchFallback() {
        val existing = listOf(
            UIMessagePart.Text("I will search first."),
            UIMessagePart.Tool(
                toolCallId = "search-1",
                toolName = "search_images",
                input = "{}",
                output = listOf(UIMessagePart.Text("""{"error":"unavailable"}""")),
            ),
        )

        assertFalse(
            hasNewActionableProviderOutput(
                before = existing,
                after = existing + UIMessagePart.Reasoning("Trying another approach"),
            )
        )
        assertFalse(
            hasNewActionableProviderOutput(
                before = existing,
                after = existing + UIMessagePart.Text(""),
            )
        )
        assertTrue(
            hasNewActionableProviderOutput(
                before = existing,
                after = existing + UIMessagePart.Text("Search is unavailable, but here is what I know."),
            )
        )
        assertTrue(
            hasNewActionableProviderOutput(
                before = existing,
                after = existing + UIMessagePart.Tool("search-2", "search_web", "{}"),
            )
        )
        assertFalse(
            shouldAddSearchFailureFallback(
                recoveringFromSearchFailure = false,
                before = existing,
                after = existing,
            )
        )
        assertTrue(
            shouldAddSearchFailureFallback(
                recoveringFromSearchFailure = true,
                before = existing,
                after = existing + UIMessagePart.Reasoning("No actionable output"),
            )
        )
        assertFalse(
            shouldAddSearchFailureFallback(
                recoveringFromSearchFailure = true,
                before = existing,
                after = existing + UIMessagePart.Text("A normal answer"),
            )
        )
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
