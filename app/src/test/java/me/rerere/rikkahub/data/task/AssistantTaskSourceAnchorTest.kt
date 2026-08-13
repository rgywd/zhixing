package me.rerere.rikkahub.data.task

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantTaskSourceAnchorTest {
    @Test
    fun `assistant regeneration resolves the preceding user source`() {
        val firstUser = UIMessage.user("第一次")
        val firstAssistant = UIMessage.assistant("第一次回复")
        val secondUser = UIMessage.user("第二次")
        val secondAssistant = UIMessage.assistant("第二次回复")
        val secondUserNode = MessageNode(messages = listOf(secondUser))
        val conversation = conversation(
            MessageNode(messages = listOf(firstUser)),
            MessageNode(messages = listOf(firstAssistant)),
            secondUserNode,
            MessageNode(messages = listOf(secondAssistant)),
        )

        assertEquals(
            AssistantTaskSourceAnchor(
                messageId = secondUser.id.toString(),
                nodeId = secondUserNode.id.toString(),
            ),
            conversation.assistantTaskSourceAnchorFor(secondAssistant),
        )
    }

    @Test
    fun `user regeneration keeps its own source anchor`() {
        val user = UIMessage.user("写入")
        val node = MessageNode(messages = listOf(user))
        val conversation = conversation(node)

        assertEquals(
            AssistantTaskSourceAnchor(user.id.toString(), node.id.toString()),
            conversation.assistantTaskSourceAnchorFor(user),
        )
    }

    @Test
    fun `message outside conversation has no source anchor`() {
        assertNull(conversation().assistantTaskSourceAnchorFor(UIMessage.assistant("未知")))
    }

    private fun conversation(vararg nodes: MessageNode) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = nodes.toList(),
    )
}
