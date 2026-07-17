package me.rerere.rikkahub.data.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkMessageParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(raw: String): WorkMessageParser.Parsed? =
        WorkMessageParser.parse(json.parseToJsonElement(raw).jsonObject)

    @Test
    fun `parses user text message`() {
        val parsed = parse("""{"role":"user","content":{"type":"text","text":"修复 CI"}}""")

        assertEquals(WorkRole.USER, parsed?.role)
        assertEquals(listOf(WorkMessagePart.Text("修复 CI")), parsed?.parts)
    }

    @Test
    fun `parses acp message and reasoning`() {
        val message = parse(
            """{"role":"agent","content":{"type":"acp","provider":"claude","data":{"type":"message","message":"完成"}}}"""
        )
        val reasoning = parse(
            """{"role":"agent","content":{"type":"acp","provider":"codex","data":{"type":"reasoning","message":"先看测试"}}}"""
        )

        assertEquals(listOf(WorkMessagePart.Text("完成")), message?.parts)
        assertEquals(WorkRole.AGENT, message?.role)
        assertEquals(listOf(WorkMessagePart.Reasoning("先看测试")), reasoning?.parts)
    }

    @Test
    fun `parses codex tool call and result envelope`() {
        val call = parse(
            """{"role":"agent","content":{"type":"codex","data":{"type":"tool-call","name":"CodexBash","callId":"c1","input":{"command":"./gradlew test"},"id":"m1"}}}"""
        )
        val result = parse(
            """{"role":"agent","content":{"type":"codex","data":{"type":"tool-call-result","callId":"c1","output":{"exit_code":0},"id":"m2"}}}"""
        )

        val callPart = call?.parts?.single() as WorkMessagePart.ToolCall
        assertEquals("CodexBash", callPart.name)
        assertEquals("c1", callPart.callId)
        assertTrue(callPart.input.contains("./gradlew test"))
        val resultPart = result?.parts?.single() as WorkMessagePart.ToolResult
        assertEquals("c1", resultPart.callId)
        assertTrue(resultPart.output.contains("exit_code"))
    }

    @Test
    fun `parses claude raw output blocks`() {
        val parsed = parse(
            """
            {"role":"agent","content":{"type":"output","data":{"type":"assistant","message":{"role":"assistant","content":[
              {"type":"thinking","thinking":"先查日志"},
              {"type":"text","text":"我看下失败原因"},
              {"type":"tool_use","id":"t1","name":"Bash","input":{"command":"git log"}}
            ]}}}}
            """.trimIndent()
        )

        assertEquals(
            listOf(
                WorkMessagePart.Reasoning("先查日志"),
                WorkMessagePart.Text("我看下失败原因"),
                WorkMessagePart.ToolCall(name = "Bash", input = """{"command":"git log"}""", callId = "t1"),
            ),
            parsed?.parts,
        )
    }

    @Test
    fun `parses legacy session event and keeps kind`() {
        val parsed = parse(
            """{"role":"session","content":{"id":"e1","role":"agent","ev":{"t":"tool-call-start","name":"CodexBash","title":"运行测试"}}}"""
        )

        assertEquals(WorkRole.AGENT, parsed?.role)
        assertEquals(listOf(WorkMessagePart.Event("tool-call-start", "运行测试")), parsed?.parts)
    }

    @Test
    fun `skips token count and keeps unknown types as raw`() {
        assertNull(parse("""{"role":"agent","content":{"type":"codex","data":{"type":"token_count","total":123}}}"""))

        val raw = parse(
            """{"role":"agent","content":{"type":"codex","data":{"type":"future_type","message":"新事件"}}}"""
        )
        assertEquals(listOf(WorkMessagePart.Raw("future_type", "新事件")), raw?.parts)
    }

    @Test
    fun `extracts permission mode from user message meta`() {
        val withMode = parse(
            """{"role":"user","content":{"type":"text","text":"继续"},"meta":{"sentFrom":"android","permissionMode":"bypassPermissions"}}"""
        )
        val withoutMode = parse("""{"role":"user","content":{"type":"text","text":"继续"}}""")

        assertEquals("bypassPermissions", withMode?.permissionMode)
        assertNull(withoutMode?.permissionMode)
    }

    @Test
    fun `parts survive polymorphic json round trip`() {
        val parts: List<WorkMessagePart> = listOf(
            WorkMessagePart.Text("hi"),
            WorkMessagePart.ToolCall(name = "Bash", input = "{}", callId = "c1"),
            WorkMessagePart.Event("ready"),
        )

        val encoded = json.encodeToString(parts)
        val decoded = json.decodeFromString<List<WorkMessagePart>>(encoded)

        assertEquals(parts, decoded)
    }
}
