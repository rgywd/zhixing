package me.rerere.rikkahub.data.workflow.codex

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexMessageProjectorTest {
    @Test
    fun `projects history into the existing rich chat parts`() {
        val detail = detail(
            CodexItem(
                itemId = "user_1",
                type = "userMessage",
                rawType = "userMessage",
                role = "user",
                text = "修一下",
                status = "completed",
                raw = buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "修一下") })
                        add(buildJsonObject { put("type", "image"); put("url", "https://example.test/bug.png") })
                    }
                },
            ),
            CodexItem("reason_1", "reasoning", "reasoning", "agent", "先检查状态", "completed"),
            CodexItem("answer_1", "agentMessage", "agentMessage", "agent", "**已经修复**", "completed"),
            CodexItem(
                "tool_1", "commandExecution", "commandExecution", "tool", "npm test", "completed",
                buildJsonObject {
                    put("command", "npm test")
                    put("aggregatedOutput", "12 tests passed")
                },
            ),
        )

        val blocks = CodexMessageProjector.project(detail)

        assertEquals(2, blocks.size)
        assertEquals(MessageRole.USER, blocks[0].role)
        assertTrue(blocks[0].parts[0] is UIMessagePart.Text)
        assertTrue(blocks[0].parts[1] is UIMessagePart.Image)
        assertEquals(MessageRole.ASSISTANT, blocks[1].role)
        assertTrue(blocks[1].parts[0] is UIMessagePart.Reasoning)
        assertEquals("**已经修复**", (blocks[1].parts[1] as UIMessagePart.Text).text)
        assertEquals("terminal", (blocks[1].parts[2] as UIMessagePart.Tool).toolName)
        assertFalse(blocks[1].loading)
    }

    @Test
    fun `marks the last block loading for a streaming turn`() {
        val blocks = CodexMessageProjector.project(
            detail(
                CodexItem("answer", "agentMessage", "agentMessage", "agent", "流式", "inProgress"),
                status = "inProgress",
            )
        )

        assertTrue(blocks.single().loading)
    }

    @Test
    fun `links a Codex approval to the existing inline tool approval UI`() {
        val detail = detail(
            CodexItem(
                "tool_1", "commandExecution", "commandExecution", "tool", "git push", "inProgress",
                buildJsonObject { put("command", "git push") },
            )
        ).copy(
            approvals = listOf(
                CodexApproval(
                    approvalId = "approval_1",
                    kind = "command",
                    summary = "允许运行 git push",
                    createdAt = 1L,
                    payload = buildJsonObject { put("itemId", "tool_1") },
                )
            )
        )

        val tool = CodexMessageProjector.project(detail).single().parts.single() as UIMessagePart.Tool

        assertEquals(ToolApprovalState.Pending, tool.approvalState)
    }

    @Test
    fun `restores phone attachment previews from the persisted remote path mapping`() {
        val detail = detail(
            CodexItem(
                "user_1", "userMessage", "userMessage", "user", null, "completed",
                buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "localImage"); put("path", "C:/uploads/bug.png") })
                        add(buildJsonObject { put("type", "mention"); put("path", "C:/uploads/spec.pdf"); put("name", "spec.pdf") })
                    }
                },
            )
        ).copy(
            attachments = mapOf(
                "C:/uploads/bug.png" to CodexAttachment("C:/uploads/bug.png", "file:///phone/bug.png", "bug.png", "image/png"),
                "C:/uploads/spec.pdf" to CodexAttachment("C:/uploads/spec.pdf", "file:///phone/spec.pdf", "spec.pdf", "application/pdf"),
            )
        )

        val parts = CodexMessageProjector.project(detail).single().parts

        assertEquals("file:///phone/bug.png", (parts[0] as UIMessagePart.Image).url)
        assertEquals("spec.pdf", (parts[1] as UIMessagePart.Document).fileName)
    }

    @Test
    fun `history snapshot and equivalent runtime event replay project identically`() {
        val finalRaw = buildJsonObject {
            put("command", "npm test")
            put("aggregatedOutput", "12 tests passed")
        }
        val history = detail(
            CodexItem("tool_1", "commandExecution", "commandExecution", "tool", "npm test", "completed", finalRaw)
        )
        var runtime: CodexItem? = null
        listOf(
            RuntimeEventPayload(
                machineId = "machine_1", threadId = "thread_1", eventId = "event_1",
                type = "item.started", at = 1, turnId = "turn_1", itemId = "tool_1",
                itemType = "commandExecution", role = "tool", text = "npm test",
                payload = buildJsonObject { put("command", "npm test") },
            ),
            RuntimeEventPayload(
                machineId = "machine_1", threadId = "thread_1", eventId = "event_2",
                type = "item.delta", at = 2, turnId = "turn_1", itemId = "tool_1",
                itemType = "commandExecution", role = "tool", text = "npm test",
                payload = buildJsonObject { put("command", "npm test"); put("aggregatedOutput", "12 tests ") },
            ),
            RuntimeEventPayload(
                machineId = "machine_1", threadId = "thread_1", eventId = "event_3",
                type = "item.completed", at = 3, turnId = "turn_1", itemId = "tool_1",
                itemType = "commandExecution", role = "tool", text = "npm test", status = "completed",
                payload = finalRaw,
            ),
        ).forEach { runtime = CodexRuntimeItemReducer.apply(runtime, it) }

        val replay = detail(requireNotNull(runtime))

        assertEquals(CodexMessageProjector.project(history), CodexMessageProjector.project(replay))
    }

    private fun detail(vararg items: CodexItem, status: String = "completed") = CodexThreadDetail(
        thread = null,
        turns = listOf(CodexTurn("turn_1", status, null, null, null, items.toList())),
    )
}
