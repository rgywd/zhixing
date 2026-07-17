package me.rerere.rikkahub.data.workflow

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
    fun `merges consecutive tool activity into one card and keeps text as bubbles`() {
        val items = buildDisplayItems(
            listOf(
                message("m1", WorkRole.USER, WorkMessagePart.Text("修复 CI")),
                message(
                    "m2", WorkRole.AGENT,
                    WorkMessagePart.ToolCall(name = "Bash", input = "./gradlew test", callId = "c1"),
                    WorkMessagePart.FileEdit(filePath = "app/src/Foo.kt"),
                ),
                message("m3", WorkRole.AGENT, WorkMessagePart.Text("已修复")),
            )
        )

        assertEquals(3, items.size)
        assertTrue(items[0] is WorkDisplayItem.UserText)
        val activity = items[1] as WorkDisplayItem.Activity
        assertEquals(listOf("Bash", "修改 Foo.kt"), activity.entries.map(WorkActivityEntry::label))
        assertEquals("已修复", (items[2] as WorkDisplayItem.AgentText).text)
    }

    @Test
    fun `successful tool results and terminal output stay out of chat while errors surface`() {
        val items = buildDisplayItems(
            listOf(
                message(
                    "m1", WorkRole.AGENT,
                    WorkMessagePart.ToolCall(name = "Bash", input = "ls", callId = "c1"),
                    WorkMessagePart.ToolResult(output = "ok", callId = "c1"),
                    WorkMessagePart.Terminal(output = "raw"),
                    WorkMessagePart.ToolResult(output = "boom", callId = "c2", isError = true),
                )
            )
        )

        val activity = items.single() as WorkDisplayItem.Activity
        assertEquals(2, activity.entries.size)
        assertTrue(activity.entries.last().isError)
    }

    @Test
    fun `noisy events are dropped and terminal ones become notes`() {
        val items = buildDisplayItems(
            listOf(
                message(
                    "m1", WorkRole.AGENT,
                    WorkMessagePart.Event("ready"),
                    WorkMessagePart.Event("task_complete"),
                )
            )
        )

        assertEquals(listOf("任务完成"), items.filterIsInstance<WorkDisplayItem.EventNote>().map { it.text })
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
}
