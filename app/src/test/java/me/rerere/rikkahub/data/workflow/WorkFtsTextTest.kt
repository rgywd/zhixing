package me.rerere.rikkahub.data.workflow

import me.rerere.rikkahub.data.db.fts.extractFtsText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkFtsTextTest {
    @Test
    fun `indexes conversation reports and questions but not tool logs or reasoning`() {
        val message = WorkMessage(
            id = "m1",
            sessionId = "s1",
            seq = 1,
            role = WorkRole.AGENT,
            parts = listOf(
                WorkMessagePart.Text("阶段汇报"),
                WorkMessagePart.ClaudeAsk("需要决定", listOf(ClaudeQuestion(question = "发布吗？"))),
                WorkMessagePart.HtmlReport("验收报告", "<p>secret body</p>"),
                WorkMessagePart.Reasoning("内部思考"),
                WorkMessagePart.ToolCall("Bash", "git push"),
                WorkMessagePart.Raw("terminal", "工具日志"),
            ),
            createdAt = 0,
        )

        val text = message.extractFtsText()
        assertTrue(text.contains("阶段汇报"))
        assertTrue(text.contains("需要决定"))
        assertTrue(text.contains("发布吗"))
        assertTrue(text.contains("验收报告"))
        assertFalse(text.contains("内部思考"))
        assertFalse(text.contains("git push"))
        assertFalse(text.contains("工具日志"))
        assertFalse(text.contains("secret body"))
    }
}
