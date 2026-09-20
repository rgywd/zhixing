package me.rerere.rikkahub.data.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunStatusTest {
    private val answered = UIMessagePart.Tool("question", "ask_user", "{}",
        output = listOf(UIMessagePart.Text("2026")), approvalState = ToolApprovalState.Answered("2026"))
    private fun status(vararg parts: UIMessagePart) = agentRunStatus(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())))

    @Test fun `final answer after tool in same message completes`() {
        assertEquals("COMPLETED", status(answered, UIMessagePart.Text("Verified 2026")))
    }
    @Test fun `unfinished tool loop does not complete`() {
        assertEquals("FAILED", status(answered))
    }
    @Test fun `pending question waits for user`() {
        assertEquals("WAITING_FOR_INPUT", status(answered.copy(output = emptyList(), approvalState = ToolApprovalState.Pending)))
    }
}
