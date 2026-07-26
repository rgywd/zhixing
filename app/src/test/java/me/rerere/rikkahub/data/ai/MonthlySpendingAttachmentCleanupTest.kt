package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MonthlySpendingAttachmentCleanupTest {
    @Test
    fun `all saves bound to one source message must succeed before cleanup`() {
        val oldUri = "file:///chat/old-bill.png"
        val imageUri = "file:///chat/july-bill.png"
        val documentUri = "file:///chat/july-bill.pdf"
        val source = userMessage(
            UIMessagePart.Image(imageUri),
            UIMessagePart.Document(documentUri, "july.pdf", "application/pdf"),
            UIMessagePart.Image("https://example.com/public.png"),
        )
        val assistant = assistantMessage(
            monthlyTool(toolCallId = "cny"),
            monthlyTool(toolCallId = "usd"),
        )
        val bound = conversationOf(
            userMessage(UIMessagePart.Image(oldUri)),
            userMessage(UIMessagePart.Text("继续")),
            source,
            assistant,
        ).bindMonthlySpendingSaveSourceMessages()

        val firstOnly = bound.completeTool(
            assistantMessageId = assistant.id,
            toolCallId = "cny",
            output = successfulSaveOutput(),
        )
        assertTrue(firstOnly.findReadyMonthlySpendingAttachmentCleanupCandidates().isEmpty())

        val completed = firstOnly.completeTool(
            assistantMessageId = assistant.id,
            toolCallId = "usd",
            output = successfulSaveOutput(),
        )
        val candidate = completed
            .findReadyMonthlySpendingAttachmentCleanupCandidates()
            .single()

        assertEquals(setOf(source.id), candidate.sourceMessageIds)
        assertEquals(
            setOf(
                MonthlySpendingToolCallRef(assistant.id, "cny"),
                MonthlySpendingToolCallRef(assistant.id, "usd"),
            ),
            candidate.toolCalls,
        )
        assertEquals(setOf(imageUri, documentUri), candidate.attachmentUris)
    }

    @Test
    fun `pending denied and failed sibling saves retain the source batch`() {
        val source = userMessage(UIMessagePart.Image("file:///chat/bill.png"))
        val assistant = assistantMessage(
            monthlyTool(toolCallId = "first"),
            monthlyTool(toolCallId = "second"),
        )
        val bound = conversationOf(source, assistant)
            .bindMonthlySpendingSaveSourceMessages()
            .completeTool(assistant.id, "first", successfulSaveOutput())

        assertTrue(bound.findReadyMonthlySpendingAttachmentCleanupCandidates().isEmpty())

        val failed = bound.completeTool(
            assistantMessageId = assistant.id,
            toolCallId = "second",
            output = listOf(UIMessagePart.Text("""{"success":false,"action":"save"}""")),
        )
        assertTrue(failed.findReadyMonthlySpendingAttachmentCleanupCandidates().isEmpty())

        val denied = bound.completeTool(
            assistantMessageId = assistant.id,
            toolCallId = "second",
            output = listOf(UIMessagePart.Text("""{"error":"denied"}""")),
            approvalState = ToolApprovalState.Denied("not now"),
        )
        assertTrue(denied.findReadyMonthlySpendingAttachmentCleanupCandidates().isEmpty())
    }

    @Test
    fun `later save in the same approval chain reuses the original source batch`() {
        val source = userMessage(UIMessagePart.Image("file:///chat/bill.png"))
        val firstAssistant = assistantMessage(monthlyTool(toolCallId = "cny"))
        val firstCompleted = conversationOf(source, firstAssistant)
            .bindMonthlySpendingSaveSourceMessages()
            .completeTool(firstAssistant.id, "cny", successfulSaveOutput())

        val secondAssistant = assistantMessage(monthlyTool(toolCallId = "usd"))
        val secondBound = firstCompleted.copy(
            messageNodes = firstCompleted.messageNodes + MessageNode.of(secondAssistant),
        ).bindMonthlySpendingSaveSourceMessages()
        assertTrue(secondBound.findReadyMonthlySpendingAttachmentCleanupCandidates().isEmpty())

        val completed = secondBound.completeTool(
            secondAssistant.id,
            "usd",
            successfulSaveOutput(),
        )
        val candidate = completed
            .findReadyMonthlySpendingAttachmentCleanupCandidates()
            .single()

        assertEquals(setOf(source.id), candidate.sourceMessageIds)
        assertEquals(2, candidate.toolCalls.size)
    }

    @Test
    fun `assistant message identity prevents reused call id from matching an old success`() {
        val oldUri = "file:///chat/old.png"
        val oldSource = userMessage(UIMessagePart.Image(oldUri))
        val oldAssistant = assistantMessage(monthlyTool(toolCallId = "reused"))
        val oldCompleted = conversationOf(oldSource, oldAssistant)
            .bindMonthlySpendingSaveSourceMessages()
            .completeTool(oldAssistant.id, "reused", successfulSaveOutput())

        val newUri = "file:///chat/new.png"
        val newSource = userMessage(UIMessagePart.Image(newUri))
        val newAssistant = assistantMessage(monthlyTool(toolCallId = "reused"))
        val failedNew = oldCompleted.copy(
            messageNodes = oldCompleted.messageNodes + listOf(
                MessageNode.of(newSource),
                MessageNode.of(newAssistant),
            ),
        )
            .bindMonthlySpendingSaveSourceMessages()
            .completeTool(
                newAssistant.id,
                "reused",
                listOf(UIMessagePart.Text("""{"success":false,"action":"save"}""")),
            )

        val candidates = failedNew.findReadyMonthlySpendingAttachmentCleanupCandidates()
        assertEquals(1, candidates.size)
        assertEquals(setOf(oldUri), candidates.single().attachmentUris)
        assertFalse(newUri in candidates.single().attachmentUris)
    }

    @Test
    fun `branch switch still verifies the exact executed assistant message`() {
        val source = userMessage(UIMessagePart.Image("file:///chat/bill.png"))
        val executingAssistant = assistantMessage(monthlyTool(toolCallId = "save"))
        val siblingAssistant = assistantMessage(UIMessagePart.Text("another branch"))
        val bound = conversationOfNodes(
            MessageNode.of(source),
            MessageNode(
                messages = listOf(executingAssistant, siblingAssistant),
                selectIndex = 0,
            ),
        ).bindMonthlySpendingSaveSourceMessages()
        val completed = bound
            .completeTool(executingAssistant.id, "save", successfulSaveOutput())
            .copy(
                messageNodes = bound.messageNodes.mapIndexed { index, node ->
                    if (index == 1) node.copy(
                        messages = completedMessages(
                            node = node,
                            assistantMessageId = executingAssistant.id,
                            toolCallId = "save",
                            output = successfulSaveOutput(),
                        ),
                        selectIndex = 1,
                    ) else node
                },
            )

        val candidate = completed
            .findReadyMonthlySpendingAttachmentCleanupCandidates()
            .single()
        assertEquals(
            setOf(MonthlySpendingToolCallRef(executingAssistant.id, "save")),
            candidate.toolCalls,
        )
    }

    @Test
    fun `redaction touches only bound source messages and retains shared references`() {
        val targetUri = "file:///chat/bill.png"
        val placeholder = "用于汇总的账单附件已删除"
        val source = userMessage(
            UIMessagePart.Image(targetUri),
            UIMessagePart.Document(targetUri, "bill.pdf", "application/pdf"),
        )
        val sibling = userMessage(
            UIMessagePart.Image(targetUri),
            UIMessagePart.Video(targetUri),
            UIMessagePart.Audio(targetUri),
        )
        val conversation = conversationOfNodes(
            MessageNode(
                messages = listOf(source, sibling),
                selectIndex = 0,
            ),
        )
        val redaction = MonthlySpendingAttachmentRedaction(
            sourceMessageIds = setOf(source.id),
            attachmentUris = setOf(targetUri),
        )

        assertTrue(conversation.containsMonthlySpendingAttachmentUris(redaction))
        val redacted = conversation.redactMonthlySpendingAttachments(redaction, placeholder)

        assertEquals(
            listOf(UIMessagePart.Text(placeholder)),
            redacted.messageNodes.single().messages[0].parts,
        )
        assertSame(sibling, redacted.messageNodes.single().messages[1])
        assertFalse(redacted.containsMonthlySpendingAttachmentUris(redaction))
        assertTrue(
            redacted.messageNodes.single().messages[1].parts.any { part ->
                when (part) {
                    is UIMessagePart.Image -> part.url == targetUri
                    is UIMessagePart.Video -> part.url == targetUri
                    is UIMessagePart.Audio -> part.url == targetUri
                    else -> false
                }
            },
        )
    }

    @Test
    fun `remote and content attachments never create a cleanup binding`() {
        val conversation = conversationOf(
            userMessage(
                UIMessagePart.Image("https://example.com/bill.png"),
                UIMessagePart.Document(
                    "content://provider/bill.pdf",
                    "bill.pdf",
                    "application/pdf",
                ),
            ),
            assistantMessage(monthlyTool()),
        )

        val bound = conversation.bindMonthlySpendingSaveSourceMessages()
        val completed = bound.completeTool(
            assistantMessageId = bound.currentMessages.last().id,
            toolCallId = "ledger-call",
            output = successfulSaveOutput(),
        )
        assertTrue(completed.findReadyMonthlySpendingAttachmentCleanupCandidates().isEmpty())
    }

    @Test
    fun `plain text user message does not bind an older attachment`() {
        val oldSource = userMessage(UIMessagePart.Image("file:///chat/old-bill.png"))
        val latestUser = userMessage(UIMessagePart.Text("请把刚才的结果写到账本"))
        val assistant = assistantMessage(monthlyTool())
        val bound = conversationOf(oldSource, latestUser, assistant)
            .bindMonthlySpendingSaveSourceMessages()
            .completeTool(
                assistantMessageId = assistant.id,
                toolCallId = "ledger-call",
                output = successfulSaveOutput(),
            )

        assertTrue(bound.findReadyMonthlySpendingAttachmentCleanupCandidates().isEmpty())
    }

    @Test
    fun `later unrelated generation cannot clean an older successful save`() {
        val source = userMessage(UIMessagePart.Image("file:///chat/bill.png"))
        val assistant = assistantMessage(monthlyTool())
        val completed = conversationOf(source, assistant)
            .bindMonthlySpendingSaveSourceMessages()
            .completeTool(
                assistantMessageId = assistant.id,
                toolCallId = "ledger-call",
                output = successfulSaveOutput(),
            )
        val successfulRef = MonthlySpendingToolCallRef(
            assistantMessageId = assistant.id,
            toolCallId = "ledger-call",
        )

        assertEquals(
            setOf(successfulRef),
            completed.successfulMonthlySpendingSaveToolCalls(),
        )
        assertTrue(
            completed.findReadyMonthlySpendingAttachmentCleanupCandidates(
                eligibleToolCalls = emptySet(),
            ).isEmpty(),
        )
        assertEquals(
            1,
            completed.findReadyMonthlySpendingAttachmentCleanupCandidates(
                eligibleToolCalls = setOf(successfulRef),
            ).size,
        )
    }

    private fun Conversation.completeTool(
        assistantMessageId: Uuid,
        toolCallId: String,
        output: List<UIMessagePart>,
        approvalState: ToolApprovalState = ToolApprovalState.Approved,
    ): Conversation = copy(
        messageNodes = messageNodes.map { node ->
            node.copy(
                messages = completedMessages(
                    node = node,
                    assistantMessageId = assistantMessageId,
                    toolCallId = toolCallId,
                    output = output,
                    approvalState = approvalState,
                ),
            )
        },
    )

    private fun completedMessages(
        node: MessageNode,
        assistantMessageId: Uuid,
        toolCallId: String,
        output: List<UIMessagePart>,
        approvalState: ToolApprovalState = ToolApprovalState.Approved,
    ): List<UIMessage> = node.messages.map { message ->
        if (message.id != assistantMessageId) return@map message
        message.copy(
            parts = message.parts.map { part ->
                if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                    part.copy(output = output, approvalState = approvalState)
                } else {
                    part
                }
            },
        )
    }

    private fun conversationOf(vararg messages: UIMessage): Conversation =
        conversationOfNodes(*messages.map(MessageNode::of).toTypedArray())

    private fun conversationOfNodes(vararg nodes: MessageNode): Conversation = Conversation(
        assistantId = Uuid.random(),
        messageNodes = nodes.toList(),
    )

    private fun userMessage(vararg parts: UIMessagePart): UIMessage = UIMessage(
        role = MessageRole.USER,
        parts = parts.toList(),
    )

    private fun assistantMessage(vararg parts: UIMessagePart): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    private fun monthlyTool(
        toolCallId: String = "ledger-call",
        approvalState: ToolApprovalState = ToolApprovalState.Pending,
    ) = UIMessagePart.Tool(
        toolCallId = toolCallId,
        toolName = "monthly_spending_summary",
        input = """{"action":"save","month":"2026-07"}""",
        approvalState = approvalState,
    )

    private fun successfulSaveOutput(): List<UIMessagePart> = listOf(
        UIMessagePart.Text("""{"success":true,"action":"save"}"""),
    )
}
