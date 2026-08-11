package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
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
    fun finalizationHookCanEnforceOneWriteToolCallPerRun() = runBlocking {
        var finalized = false
        val tool = buildMemoryDocumentTools(
            json = Json,
            onFinalize = {
                check(!finalized)
                finalized = true
            },
            onRead = { document() },
            onWrite = { _, _, _, _, _, _, _ -> document() },
            onReplace = { _, _, _, _, _ -> document() },
            onAppend = { _, _, _, _ -> document() },
            onDelete = { _, _ -> },
        ).last()

        tool.execute(buildJsonObject {})
        assertTrue(runCatching { tool.execute(buildJsonObject {}) }.isFailure)
    }

    @Test
    fun chatSourceMustQuoteCurrentUserMessageExactly() {
        val user = UIMessage.user("请记住我偏好中文回复")
        val source = MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = "conversation-1",
            messageId = user.id.toString(),
            quote = "偏好中文回复",
        )

        val validated = validateMemoryDocumentChatSources(
            listOf(source),
            conversationId = "conversation-1",
            messages = listOf(user),
        )
        assertEquals("偏好中文回复", validated.single().quote)

        val forged = runCatching {
            validateMemoryDocumentChatSources(
                listOf(source.copy(quote = "模型推断的内容")),
                conversationId = "conversation-1",
                messages = listOf(user, UIMessage.assistant("assistant reply")),
            )
        }
        assertTrue(forged.isFailure)
    }

    @Test
    fun versionConflictReturnsTheCurrentDocument() = runBlocking {
        val current = document().copy(content = "- [stated] 当前内容。", version = 4)
        val tool = buildMemoryDocumentTools(
            json = Json,
            onRead = { current },
            onWrite = { _, _, _, _, _, _, _ -> throw MemoryDocumentConflictException(current) },
            onReplace = { _, _, _, _, _ -> current },
            onAppend = { _, _, _, _ -> current },
            onDelete = { _, _ -> },
        ).last()

        val result = tool.execute(
            buildJsonObject {
                put("action", "write")
                put("path", "/profile.md")
                put("if_version", 3)
                put("name", "Profile")
                put("description", "Stable profile")
                put("content", "- [stated] 旧内容。")
                put("sources", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("conversationId", "conversation")
                        put("messageId", "message")
                        put("quote", "旧内容")
                    })
                })
            }
        ).single() as UIMessagePart.Text

        assertTrue(result.text.contains("MEMORY_VERSION_CONFLICT"))
        assertTrue(result.text.contains("\"version\":4"))
        assertTrue(result.text.contains("当前内容"))
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

    private fun action(value: String) = buildJsonObject { put("action", value) }
}
