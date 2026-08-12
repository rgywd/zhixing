package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ToolExecutionException
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MemoryDocument
import me.rerere.rikkahub.data.model.MemoryDocumentSource
import me.rerere.rikkahub.data.model.MemoryDocumentSourceType
import me.rerere.rikkahub.data.repository.MemoryDocumentConflictException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDocumentToolsTest {
    @Test
    fun memoryAndRawHistoryRemainDifferentTools() {
        val tools = memoryTools()
        assertEquals(listOf("memory_read", "memory_write"), tools.map { it.name })
        assertTrue(tools.first().description.contains("never searches raw chat history"))
        assertTrue(tools.last().description.contains("never changes raw conversation history"))
        assertTrue(tools.last().description.contains("special \"remember\" phrase is not required"))
        assertTrue(tools.last().description.contains("app binds"))
        assertTrue(tools.last().description.contains("background memory service"))
    }

    @Test
    fun onlyDeleteNeedsApproval() {
        val write = memoryTools().last()
        assertFalse(write.needsApproval(action("write")))
        assertFalse(write.needsApproval(action("append")))
        assertTrue(write.needsApproval(action("delete")))
    }

    @Test
    fun everyRunCanFinalizeWithoutInventingAMemory() = runBlocking {
        val result = memoryTools().last().execute(buildJsonObject {}).single() as UIMessagePart.Text
        assertTrue(result.text.contains("\"changed\":false"))
    }

    @Test
    fun memoryWriteSchemaAcceptsQuotesWithoutExposingInternalIds() {
        val schema = memoryTools().last().parameters() as InputSchema.Obj
        val sourcesSchema = schema.properties.getValue("sources").toString()

        assertTrue(sourcesSchema.contains("quote"))
        assertFalse(sourcesSchema.contains("conversationId"))
        assertFalse(sourcesSchema.contains("messageId"))
    }

    @Test
    fun failedWriteCanRetryButSuccessfulWriteFinalizesTheRun() = runBlocking {
        var finalized = false
        var attempts = 0
        val tool = buildMemoryDocumentTools(
            json = Json,
            checkCanFinalize = {
                if (finalized) throw ToolExecutionException("MEMORY_ALREADY_FINALIZED")
            },
            onFinalize = {
                finalized = true
            },
            onRead = { document() },
            onWrite = { _, _, _, _, _, _, _ -> document() },
            onReplace = { _, _, _, _, _ -> document() },
            onAppend = { _, _, _, _ ->
                attempts += 1
                if (attempts == 1) error("simulated validation failure")
                document()
            },
            onDelete = { _, _ -> },
        ).last()

        val input = appendInput()
        val firstFailure = runCatching { tool.execute(input) }.exceptionOrNull()
        assertEquals("MEMORY_WRITE_REJECTED", (firstFailure as ToolExecutionException).code)
        assertFalse(finalized)

        val result = tool.execute(input).single() as UIMessagePart.Text
        assertTrue(result.text.contains("\"version\":1"))
        assertTrue(finalized)

        val duplicate = runCatching { tool.execute(input) }.exceptionOrNull()
        assertEquals("MEMORY_ALREADY_FINALIZED", (duplicate as ToolExecutionException).code)
    }

    @Test
    fun hostBindsChatSourceToTheLatestMatchingUserMessage() {
        val olderUser = UIMessage.user("我偏好中文回复")
        val latestUser = UIMessage.user("请记住我偏好中文回复")
        val source = MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = "model-supplied-conversation",
            messageId = olderUser.id.toString(),
            quote = "偏好中文回复",
        )

        val bound = bindMemoryDocumentChatSources(
            listOf(source),
            conversationId = "conversation-1",
            messages = listOf(olderUser, latestUser),
        )
        assertEquals("conversation-1", bound.single().conversationId)
        assertEquals(latestUser.id.toString(), bound.single().messageId)
        assertEquals("偏好中文回复", bound.single().quote)

        val unmatched = runCatching {
            bindMemoryDocumentChatSources(
                listOf(source.copy(quote = "模型推断的内容")),
                conversationId = "conversation-1",
                messages = listOf(latestUser, UIMessage.assistant("assistant reply")),
            )
        }.exceptionOrNull()
        assertEquals("MEMORY_SOURCE_INVALID", (unmatched as ToolExecutionException).code)
    }

    @Test
    fun versionConflictReturnsTheCurrentDocument() = runBlocking {
        val current = document().copy(content = "- [stated] 当前内容。", version = 4)
        var conflict = true
        var finalized = false
        val tool = buildMemoryDocumentTools(
            json = Json,
            checkCanFinalize = {
                if (finalized) throw ToolExecutionException("MEMORY_ALREADY_FINALIZED")
            },
            onFinalize = { finalized = true },
            onRead = { current },
            onWrite = { _, _, _, _, _, _, _ ->
                if (conflict) throw MemoryDocumentConflictException(current)
                current
            },
            onReplace = { _, _, _, _, _ -> current },
            onAppend = { _, _, _, _ -> current },
            onDelete = { _, _ -> },
        ).last()

        val input =
            buildJsonObject {
                put("action", "write")
                put("path", "/profile.md")
                put("if_version", 3)
                put("name", "Profile")
                put("description", "Stable profile")
                put("content", "- [stated] 旧内容。")
                put("sources", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("quote", "旧内容")
                    })
                })
            }
        val result = tool.execute(input).single() as UIMessagePart.Text

        assertTrue(result.text.contains("MEMORY_VERSION_CONFLICT"))
        assertTrue(result.text.contains("\"version\":4"))
        assertTrue(result.text.contains("当前内容"))
        assertFalse(finalized)

        conflict = false
        val retried = tool.execute(input).single() as UIMessagePart.Text
        assertTrue(retried.text.contains("\"version\":4"))
        assertTrue(finalized)
    }

    @Test
    fun readReturnsVirtualMarkdownWithFrontmatterAndStatedBody() = runBlocking {
        val source = MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = "conversation",
            messageId = "message",
            quote = "我偏好中文",
        )
        val tool = buildMemoryDocumentTools(
            json = Json,
            onRead = { document().copy(content = "- [stated] 我偏好中文。", sources = listOf(source)) },
            onWrite = { _, _, _, _, _, _, _ -> document() },
            onReplace = { _, _, _, _, _ -> document() },
            onAppend = { _, _, _, _ -> document() },
            onDelete = { _, _ -> },
        ).first()

        val result = tool.execute(buildJsonObject { put("path", "/profile.md") })
            .single() as UIMessagePart.Text
        assertTrue(result.text.contains("name: \\\"Profile\\\""))
        assertTrue(result.text.contains("sources:"))
        assertTrue(result.text.contains("[stated]"))
    }

    private fun memoryTools() = buildMemoryDocumentTools(
        json = Json,
        onRead = { document() },
        onWrite = { _, _, _, _, _, _, _ -> document() },
        onReplace = { _, _, _, _, _ -> document() },
        onAppend = { _, _, _, _ -> document() },
        onDelete = { _, _ -> },
    )

    private fun document() = MemoryDocument(
        scopeId = "__global__",
        path = "/profile.md",
        name = "Profile",
        description = "Stable profile",
    )

    private fun appendInput() = buildJsonObject {
        put("action", "append")
        put("path", "/profile.md")
        put("if_version", 1)
        put("content", "- [stated] 用户偏好中文回复。")
        put("sources", kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject { put("quote", "偏好中文回复") })
        })
    }

    private fun action(value: String) = buildJsonObject { put("action", value) }
}
