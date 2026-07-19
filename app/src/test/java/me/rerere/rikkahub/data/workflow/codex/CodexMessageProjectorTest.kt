package me.rerere.rikkahub.data.workflow.codex

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.rikkahub.data.work.AppServerAttachedFile
import me.rerere.rikkahub.data.work.AppServerAttachmentManifest
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
    fun `hides local attachment manifest while retaining an attachment label`() {
        val prompt = AppServerAttachmentManifest.append(
            "检查这个文件",
            listOf(AppServerAttachedFile("spec.md", "C:/uploads/spec.md", "text/markdown")),
        )
        val detail = detail(
            CodexItem(
                "user_1", "userMessage", "userMessage", "user", prompt, "completed",
                buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                        add(buildJsonObject { put("type", "mention"); put("path", "C:/uploads/spec.md"); put("name", "spec.md") })
                    }
                },
            )
        )

        val parts = CodexMessageProjector.project(detail).single().parts.filterIsInstance<UIMessagePart.Text>()

        assertEquals(listOf("检查这个文件", "@spec.md"), parts.map { it.text })
        assertTrue(parts.none { it.text.contains("zhixing_file_attachments") })
    }

    @Test
    fun `keeps a native document part for an attachment uploaded in this session`() {
        val prompt = AppServerAttachmentManifest.append(
            "检查这个文件",
            listOf(AppServerAttachedFile("spec.md", "C:/uploads/spec.md", "text/markdown")),
        )
        val detail = detail(
            CodexItem(
                "user_1", "userMessage", "userMessage", "user", prompt, "completed",
                buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                    }
                },
            )
        ).copy(
            attachments = mapOf(
                "C:/uploads/spec.md" to CodexAttachment(
                    "C:/uploads/spec.md",
                    "content://phone/spec.md",
                    "spec.md",
                    "text/markdown",
                )
            )
        )

        val parts = CodexMessageProjector.project(detail).single().parts

        assertEquals("检查这个文件", (parts[0] as UIMessagePart.Text).text)
        assertEquals("spec.md", (parts[1] as UIMessagePart.Document).fileName)
    }

    @Test
    fun `structured slash inputs do not duplicate the visible command`() {
        val detail = detail(
            CodexItem(
                "user_1", "userMessage", "userMessage", "user", "/imagegen 画一张图", "completed",
                buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "/imagegen 画一张图") })
                        add(buildJsonObject { put("type", "skill"); put("name", "imagegen"); put("path", "C:/skills/imagegen/SKILL.md") })
                        add(buildJsonObject { put("type", "mention"); put("name", "imagegen"); put("path", "app://imagegen") })
                    }
                },
            )
        )

        val parts = CodexMessageProjector.project(detail).single().parts.filterIsInstance<UIMessagePart.Text>()

        assertEquals(listOf("/imagegen 画一张图"), parts.map { it.text })
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
                payload = buildJsonObject { put("aggregatedOutput", "12 tests passed") },
            ),
        ).forEach { runtime = CodexRuntimeItemReducer.apply(runtime, it) }

        val replay = detail(requireNotNull(runtime))

        assertEquals(CodexMessageProjector.project(history), CodexMessageProjector.project(replay))
    }

    @Test
    fun `full structured history and cumulative runtime replay have the same rich rendering`() {
        val expectedItems = listOf(
            CodexItem(
                "user_1", "userMessage", "userMessage", "user", "开始", "completed",
                buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "开始") }) } },
            ),
            CodexItem(
                "plan_1", "plan", "plan", "agent", "先检查再实现", "completed",
                buildJsonObject {
                    put("explanation", "执行计划")
                    putJsonArray("plan") {
                        add(buildJsonObject { put("step", "先检查"); put("status", "completed") })
                        add(buildJsonObject { put("step", "再实现"); put("status", "in_progress") })
                    }
                },
            ),
            CodexItem("reason_1", "reasoning", "reasoning", "agent", "分析依赖", "completed"),
            CodexItem(
                "file_1", "fileChange", "fileChange", "tool", "修改文件", "completed",
                buildJsonObject { put("output", "patched") },
            ),
            CodexItem(
                "mcp_1", "mcpToolCall", "mcpToolCall", "tool", "读取 issue", "completed",
                buildJsonObject { put("namespace", "github"); put("tool", "get_issue"); put("result", "#49") },
            ),
            CodexItem(
                "web_1", "webSearch", "webSearch", "tool", "搜索", "completed",
                buildJsonObject { put("query", "official docs") },
            ),
            CodexItem(
                "collab_1", "collabAgentToolCall", "collabAgentToolCall", "tool", "复核", "completed",
                buildJsonObject { put("tool", "spawn_agent"); put("result", "PASS") },
            ),
            CodexItem("answer_1", "agentMessage", "agentMessage", "agent", "完成", "completed"),
        )
        val replayedItems = expectedItems.mapIndexed { index, item ->
            CodexRuntimeItemReducer.apply(
                current = null,
                event = RuntimeEventPayload(
                    machineId = "machine_1",
                    threadId = "thread_1",
                    eventId = "event_$index",
                    type = "item.completed",
                    at = index.toLong() + 1,
                    turnId = "turn_1",
                    itemId = item.itemId,
                    itemType = item.rawType,
                    role = item.role,
                    text = item.text,
                    status = item.status,
                    payload = item.raw,
                ),
            )
        }

        val history = detail(*expectedItems.toTypedArray())
        val replay = detail(*replayedItems.toTypedArray())
        assertEquals(expectedItems, replayedItems)
        assertEquals(CodexMessageProjector.project(history), CodexMessageProjector.project(replay))
    }

    @Test
    fun `projects complete multi turn history including plan file MCP web and collaboration items`() {
        val detail = CodexThreadDetail(
            thread = null,
            turns = listOf(
                CodexTurn(
                    turnId = "turn_1",
                    status = "completed",
                    startedAt = 1,
                    completedAt = 2,
                    error = null,
                    items = listOf(
                        CodexItem("user_1", "userMessage", "userMessage", "user", "调研并实现", "completed"),
                        CodexItem(
                            "plan_1", "plan", "plan", "agent", "1. 调研\n2. 实现", "completed",
                            buildJsonObject {
                                put("explanation", "先调研后实现")
                                putJsonArray("plan") {
                                    add(buildJsonObject { put("step", "调研"); put("status", "completed") })
                                    add(buildJsonObject { put("step", "实现"); put("status", "in_progress") })
                                }
                            },
                        ),
                        CodexItem(
                            "web_1", "webSearch", "webSearch", "tool", "查询官方文档", "completed",
                            buildJsonObject { put("query", "Codex app-server") },
                        ),
                        CodexItem(
                            "mcp_1", "mcpToolCall", "mcpToolCall", "tool", "读取 issue", "completed",
                            buildJsonObject { put("namespace", "github"); put("tool", "get_issue"); put("result", "issue #49") },
                        ),
                    ),
                ),
                CodexTurn(
                    turnId = "turn_2",
                    status = "completed",
                    startedAt = 3,
                    completedAt = 4,
                    error = null,
                    items = listOf(
                        CodexItem("user_2", "userMessage", "userMessage", "user", "继续完成", "completed"),
                        CodexItem(
                            "collab_1", "collabAgentToolCall", "collabAgentToolCall", "tool", "审查实现", "completed",
                            buildJsonObject { put("tool", "spawn_agent"); put("result", "PASS") },
                        ),
                        CodexItem(
                            "file_1", "fileChange", "fileChange", "tool", "修改文件", "completed",
                            buildJsonObject { putJsonArray("changes") { add(buildJsonObject { put("path", "app.kt") }) } },
                        ),
                        CodexItem("answer_2", "agentMessage", "agentMessage", "agent", "**已完成并验证**", "completed"),
                    ),
                ),
            ),
        )

        val blocks = CodexMessageProjector.project(detail)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT), blocks.map { it.role })
        val firstAssistant = blocks[1].parts
        val plan = firstAssistant[0] as UIMessagePart.Tool
        assertEquals("plan_1", plan.toolCallId)
        assertEquals("update_plan", plan.toolName)
        assertEquals("先调研后实现", plan.inputAsJson().jsonObject["explanation"]?.jsonPrimitive?.content)
        assertEquals("completed", plan.inputAsJson().jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals("web_search", (firstAssistant[1] as UIMessagePart.Tool).toolName)
        assertEquals("github.get_issue", (firstAssistant[2] as UIMessagePart.Tool).toolName)
        val secondAssistant = blocks[3].parts
        assertEquals("spawn_agent", (secondAssistant[0] as UIMessagePart.Tool).toolName)
        assertEquals("file_change", (secondAssistant[1] as UIMessagePart.Tool).toolName)
        assertEquals("**已完成并验证**", (secondAssistant[2] as UIMessagePart.Text).text)
    }

    @Test
    fun `opaque diagnostics are retained without leaking into chat`() {
        val opaque = CodexItem(
            itemId = "opaque-1",
            type = "opaqueNotification",
            rawType = "opaqueNotification",
            role = "system",
            text = null,
            status = "completed",
            raw = buildJsonObject {
                put("method", "future/event")
                put("payload", "internal")
            },
        )

        assertTrue(CodexMessageProjector.project(detail(opaque)).isEmpty())
    }

    private fun detail(vararg items: CodexItem, status: String = "completed") = CodexThreadDetail(
        thread = null,
        turns = listOf(CodexTurn("turn_1", status, null, null, null, items.toList())),
    )
}
