package me.rerere.rikkahub.data.workflow

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkSessionDisplayTest {
    private fun message(id: String, role: WorkRole, vararg parts: WorkMessagePart) = WorkMessage(
        id = id,
        sessionId = "s1",
        seq = id.hashCode().toLong(),
        role = role,
        parts = parts.toList(),
        createdAt = 0,
    )

    @Test
    fun `maps text and reasoning into home ui parts and merges consecutive agent messages`() {
        val items = buildWorkChatItems(
            listOf(
                message("m1", WorkRole.USER, WorkMessagePart.Text("修复 CI")),
                message("m2", WorkRole.AGENT, WorkMessagePart.Reasoning("先看日志")),
                message("m3", WorkRole.AGENT, WorkMessagePart.Text("已修复")),
            )
        )

        assertEquals(2, items.size)
        val user = items[0] as WorkChatItem.PartsBlock
        assertEquals(MessageRole.USER, user.role)
        assertEquals(listOf<UIMessagePart>(UIMessagePart.Text("修复 CI")), user.parts)
        val agent = items[1] as WorkChatItem.PartsBlock
        assertEquals(MessageRole.ASSISTANT, agent.role)
        assertEquals(2, agent.parts.size)
        assertTrue(agent.parts[0] is UIMessagePart.Reasoning)
        assertEquals("已修复", (agent.parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `pairs tool call with result across messages`() {
        val items = buildWorkChatItems(
            listOf(
                message(
                    "m1", WorkRole.AGENT,
                    WorkMessagePart.ToolCall(name = "grep", input = """{"pattern":"TODO"}""", callId = "tc1", title = "搜索 TODO"),
                ),
                message("m2", WorkRole.AGENT, WorkMessagePart.ToolResult(output = "3 hits", callId = "tc1")),
            )
        )

        val block = items.single() as WorkChatItem.PartsBlock
        val tool = block.parts.single() as UIMessagePart.Tool
        assertEquals("tc1", tool.toolCallId)
        assertEquals("搜索 TODO", tool.toolName)
        assertEquals("3 hits", (tool.output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `events become notes and lifecycle noise is dropped`() {
        val items = buildWorkChatItems(
            listOf(
                message(
                    "m1", WorkRole.AGENT,
                    WorkMessagePart.Event("turn-end", "failed"),
                    WorkMessagePart.Event("ready"),
                    WorkMessagePart.Event("service", "**Service:** connected"),
                )
            )
        )

        assertEquals(
            listOf("本轮执行失败", "**Service:** connected"),
            items.filterIsInstance<WorkChatItem.Note>().map(WorkChatItem.Note::text),
        )
    }

    @Test
    fun `stats count tools files and failures`() {
        val stats = sessionStats(
            listOf(
                message(
                    "m1", WorkRole.AGENT,
                    WorkMessagePart.ToolCall(name = "Bash"),
                    WorkMessagePart.ToolCall(name = "Bash"),
                    WorkMessagePart.ToolResult(output = "boom", isError = true),
                    WorkMessagePart.FileEdit(filePath = "a.kt"),
                    WorkMessagePart.FileEdit(filePath = "a.kt"),
                    WorkMessagePart.FileEdit(filePath = "b.kt"),
                )
            )
        )

        assertEquals(WorkSessionStats(toolCalls = 2, editedFiles = 2, failures = 1), stats)
    }

    @Test
    fun `Claude ask stays pending until a later user reply and html becomes report card`() {
        val question = ClaudeQuestion(question = "发布到哪里？", options = listOf("测试", "生产"))
        val pending = buildWorkChatItems(
            listOf(message("1", WorkRole.AGENT, WorkMessagePart.ClaudeAsk("请选择", listOf(question))))
        ).single() as WorkChatItem.Ask
        val answered = buildWorkChatItems(
            listOf(
                WorkMessage("ask", "s1", 1, WorkRole.AGENT, listOf(WorkMessagePart.ClaudeAsk("请选择", listOf(question))), 0),
                WorkMessage("reply", "s1", 2, WorkRole.USER, listOf(WorkMessagePart.Text("测试")), 0),
                WorkMessage("html", "s1", 3, WorkRole.AGENT, listOf(WorkMessagePart.HtmlReport("报告", "<p>ok</p>")), 0),
            )
        )

        assertEquals(false, pending.answered)
        assertTrue(answered.filterIsInstance<WorkChatItem.Ask>().single().answered)
        assertEquals("报告", answered.filterIsInstance<WorkChatItem.HtmlReport>().single().title)
    }
}
