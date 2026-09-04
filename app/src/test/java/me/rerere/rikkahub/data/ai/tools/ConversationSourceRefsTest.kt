package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.ToolExecutionException
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSourceRefsTest {
    @Test
    fun sourceRefRoundTripsAndResolvesOnlyTheSelectedUserMessage() {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        val unselected = UIMessage.user("旧分支内容")
        val selected = UIMessage.user("我租房用的是房东的 WiFi")
        val node = MessageNode(
            id = Uuid.random(),
            messages = listOf(unselected, selected),
            selectIndex = 1,
        )
        val conversation = Conversation(
            id = conversationId,
            assistantId = assistantId,
            title = "Homelab",
            messageNodes = listOf(node),
        )
        val sourceRef = ConversationSourceRef(conversationId, node.id, selected.id)

        assertEquals(sourceRef, ConversationSourceRef.decode(sourceRef.encode()))
        val resolved = resolveSelectedUserSource(
            conversation = conversation,
            sourceRef = sourceRef,
            assistantId = assistantId,
            excludedConversationId = null,
        )
        assertEquals(listOf("我租房用的是房东的 WiFi"), resolved.textParts)

        val wrongBranch = runCatching {
            resolveSelectedUserSource(
                conversation = conversation,
                sourceRef = sourceRef.copy(messageId = unselected.id),
                assistantId = assistantId,
                excludedConversationId = null,
            )
        }.exceptionOrNull()
        assertEquals("MEMORY_SOURCE_ROLE_INVALID", (wrongBranch as ToolExecutionException).code)
    }

    @Test
    fun sourceResolutionRejectsAnotherAssistantAndTheCurrentConversation() {
        val assistantId = Uuid.random()
        val message = UIMessage.user("历史事实")
        val node = MessageNode(messages = listOf(message))
        val conversation = Conversation(
            assistantId = assistantId,
            messageNodes = listOf(node),
        )
        val sourceRef = ConversationSourceRef(conversation.id, node.id, message.id)

        listOf(
            runCatching {
                resolveSelectedUserSource(
                    conversation,
                    sourceRef,
                    assistantId = Uuid.random(),
                    excludedConversationId = null,
                )
            }.exceptionOrNull(),
            runCatching {
                resolveSelectedUserSource(
                    conversation,
                    sourceRef,
                    assistantId = assistantId,
                    excludedConversationId = conversation.id,
                )
            }.exceptionOrNull(),
        ).forEach { error ->
            assertEquals("MEMORY_SOURCE_NOT_FOUND", (error as ToolExecutionException).code)
        }
    }

    @Test
    fun malformedSourceRefIsRejected() {
        val error = runCatching { ConversationSourceRef.decode("v2.invalid") }.exceptionOrNull()
        assertEquals("MEMORY_SOURCE_REF_INVALID", (error as ToolExecutionException).code)
    }
}
