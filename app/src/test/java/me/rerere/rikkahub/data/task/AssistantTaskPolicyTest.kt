package me.rerere.rikkahub.data.task

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTaskPolicyTest {
    private val json = Json

    @Test
    fun `single read stays chat while second tool becomes a visible task`() {
        val firstRead = step(toolName = "search_web", ordinal = 1)
        val secondRead = step(toolName = "knowledge_search", ordinal = 2)

        assertFalse(firstRead.requiresVisibleTask(json))
        assertTrue(secondRead.requiresVisibleTask(json))
        assertFalse(secondRead.requiresDurableTask(json))
    }

    @Test
    fun `life context read stays in chat and uses a natural progress label`() {
        val lifeContextRead = step(toolName = "get_life_context", ordinal = 1)

        assertFalse(lifeContextRead.requiresVisibleTask(json))
        assertFalse(lifeContextRead.requiresDurableTask(json))
        assertEquals("正在读取当前生活状态", lifeContextRead.progressText())
    }

    @Test
    fun `writes external calls and user questions require durable tasks`() {
        assertTrue(step("task_create").requiresDurableTask(json))
        assertTrue(step("gh").requiresDurableTask(json))
        assertTrue(step("mcp__life__digest").requiresDurableTask(json))
        assertTrue(step("ask_user", requiresUserAnswer = true).requiresDurableTask(json))
        assertTrue(
            step(
                toolName = "monthly_spending_summary",
                input = """{"action":"save"}""",
            ).requiresDurableTask(json)
        )
        assertFalse(
            step(
                toolName = "monthly_spending_summary",
                input = """{"action":"query"}""",
            ).requiresDurableTask(json)
        )
        assertTrue(
            step(
                toolName = "memory_write",
                input = """{"action":"append"}""",
            ).requiresDurableTask(json)
        )
        assertFalse(
            step(
                toolName = "memory_write",
                input = """{"action":"no_change"}""",
            ).requiresDurableTask(json)
        )
    }

    @Test
    fun `terminal states cannot move backwards and retry increments only from failure`() {
        assertTrue(
            canTransitionAssistantTask(
                AssistantTaskStatus.RUNNING,
                AssistantTaskStatus.WAITING_FOR_INPUT,
            )
        )
        assertTrue(
            canTransitionAssistantTask(
                AssistantTaskStatus.FAILED_RETRYABLE,
                AssistantTaskStatus.RUNNING,
            )
        )
        assertFalse(
            canTransitionAssistantTask(
                AssistantTaskStatus.COMPLETED,
                AssistantTaskStatus.RUNNING,
            )
        )
        assertFalse(
            canTransitionAssistantTask(
                AssistantTaskStatus.STOPPED,
                AssistantTaskStatus.RUNNING,
            )
        )
    }

    @Test
    fun `event text and references are bounded and redact common credentials`() {
        val text = sanitizeUserFacingText(
            "Authorization: secret-value\n正在创建事项",
            fallback = "fallback",
        )
        val ref = sanitizeReference("https://example.com/result?token=secret#fragment")

        assertFalse(text.contains("secret-value"))
        assertEquals("https://example.com/result", ref)
        assertTrue(text.length <= 160)
    }

    @Test
    fun `task title keeps the goal and removes model facing tool instructions`() {
        assertEquals(
            "Saturday August 15 from Beijing",
            naturalizeAssistantTaskTitle(
                "Saturday August 15 from Beijing. Now call plan_create to create the plan.",
            ),
        )
        assertEquals(
            "Before helping me plan Suzhou",
            naturalizeAssistantTaskTitle(
                "Before helping me plan Suzhou, use ask_user to clarify my preference.",
            ),
        )
        assertFalse(naturalizeAssistantTaskTitle("Call mcp__life__digest now").contains("mcp__"))
        assertEquals(
            "PHONE REGRESSION",
            naturalizeAssistantTaskTitle("Create a task named PHONE REGRESSION for tomorrow at 20:30."),
        )
    }

    @Test
    fun `agenda write result becomes natural user presentation while tool json remains complete`() {
        val taskId = "bc43c5fe-7c3b-47d1-bff8-28d8a8299abc"
        val tool = UIMessagePart.Tool(
            toolCallId = "create-1",
            toolName = "task_create",
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    """{"success":true,"task":{"id":"$taskId","title":"PHONE REGRESSION","status":"PENDING","source":"CHAT","recurrence_frequency":"DAILY"}}""",
                ),
            ),
        )
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)),
            UIMessage.assistant("任务已创建，ID: $taskId，状态为 PENDING，来源 CHAT，重复 DAILY。"),
        )

        val presentation = requireNotNull(extractAssistantTaskResultPresentation(messages, json))
        val presented = applyAssistantTaskResultPresentation(messages, presentation)

        assertEquals("PHONE REGRESSION", presentation.title)
        assertEquals("已创建待办「PHONE REGRESSION」。", presented.last().toText())
        assertFalse(presented.last().toText().contains(taskId))
        assertFalse(presented.last().toText().contains("PENDING"))
        assertFalse(presented.last().toText().contains("CHAT"))
        assertFalse(presented.last().toText().contains("DAILY"))
        assertTrue((presented.first().parts.single() as UIMessagePart.Tool).output.single().let {
            (it as UIMessagePart.Text).text.contains(taskId)
        })
    }

    @Test
    fun `agenda presentation removes only its technical lines and keeps other same turn results`() {
        val taskId = "bc43c5fe-7c3b-47d1-bff8-28d8a8299abc"
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "create-1",
                        toolName = "task_create",
                        input = "{}",
                        output = listOf(
                            UIMessagePart.Text(
                                """{"success":true,"task":{"id":"$taskId","title":"苏州周末行程","status":"PENDING","source":"CHAT","recurrence_frequency":"DAILY"}}""",
                            ),
                        ),
                    ),
                ),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("已创建事项：ID $taskId，状态 PENDING，来源 CHAT，重复 DAILY。"),
                    UIMessagePart.Text("资料已保存到知识库。"),
                    UIMessagePart.Text("下一步：可以继续调整行程。"),
                ),
            ),
        )

        val presentation = requireNotNull(extractAssistantTaskResultPresentation(messages, json))
        val finalText = applyAssistantTaskResultPresentation(messages, presentation).last().toText()

        assertEquals(1, Regex(Regex.escape(presentation.message)).findAll(finalText).count())
        assertTrue(finalText.contains("资料已保存到知识库。"))
        assertTrue(finalText.contains("下一步：可以继续调整行程。"))
        assertFalse(finalText.contains(taskId))
        assertFalse(finalText.contains("PENDING"))
        assertFalse(finalText.contains("CHAT"))
        assertFalse(finalText.contains("DAILY"))
    }

    @Test
    fun `recurring task completion stays a completion when the next instance remains pending`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "complete-1",
                        toolName = "task_complete",
                        input = "{}",
                        output = listOf(
                            UIMessagePart.Text(
                                """{"success":true,"task":{"title":"晨间简报","completed":false,"status":"PENDING","recurrence_frequency":"DAILY"}}""",
                            ),
                        ),
                    ),
                ),
            ),
            UIMessage.assistant("已完成，状态为 PENDING。"),
        )

        val presentation = requireNotNull(extractAssistantTaskResultPresentation(messages, json))

        assertEquals("已完成待办「晨间简报」。", presentation.message)
    }

    @Test
    fun `historical agenda write does not replace a later ordinary assistant reply`() {
        val messages = listOf(
            successfulTaskCreate("old-create", "旧事项"),
            UIMessage.assistant("旧事项已创建。"),
            UIMessage.assistant("这是新的普通回答。"),
        )

        val beforeGeneration = successfulAgendaPresentationToolCallIds(messages, json)

        assertNull(
            extractAssistantTaskResultPresentation(
                messages = messages,
                json = json,
                previousSuccessfulAgendaToolCallIds = beforeGeneration,
            ),
        )
        assertEquals("这是新的普通回答。", messages.last().toText())
    }

    @Test
    fun `historical agenda write does not supply a title for a later non agenda task`() {
        val messages = listOf(
            successfulTaskCreate("old-create", "旧事项"),
            UIMessage.assistant("旧事项已创建。"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "memory-write",
                        toolName = "memory_write",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("""{"success":true,"path":"/preferences.md"}""")),
                    ),
                ),
            ),
            UIMessage.assistant("已记住你的偏好。"),
        )

        val beforeGeneration = successfulAgendaPresentationToolCallIds(messages, json)

        assertNull(
            extractAssistantTaskResultPresentation(
                messages = messages,
                json = json,
                previousSuccessfulAgendaToolCallIds = beforeGeneration,
            ),
        )
        assertEquals("已记住你的偏好。", messages.last().toText())
    }

    private fun successfulTaskCreate(toolCallId: String, title: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = toolCallId,
                toolName = "task_create",
                input = "{}",
                output = listOf(
                    UIMessagePart.Text("""{"success":true,"task":{"title":"$title"}}"""),
                ),
            ),
        ),
    )

    private fun step(
        toolName: String,
        ordinal: Int = 1,
        input: String = "{}",
        requiresUserAnswer: Boolean = false,
    ) = AssistantTaskStep(
        toolName = toolName,
        toolCallId = "call-$ordinal",
        input = input,
        ordinal = ordinal,
        requiresUserAnswer = requiresUserAnswer,
        hasUserAnswer = false,
    )
}
