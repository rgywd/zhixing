package me.rerere.rikkahub.data.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** Actual chat engine + HTTP provider + Room. No external model credentials or network services. */
@RunWith(AndroidJUnit4::class)
class AgentRuntimeInstrumentedTest {
    @Test fun chatCreatesChildDelegatesRelaysQuestionAndReturnsResult() = runBlocking {
        val koin = GlobalContext.get()
        val store = koin.get<SettingsStore>()
        val chat = koin.get<ChatService>()
        val database = koin.get<AppDatabase>()
        val original = store.settingsFlowRaw.first()
        val server = MockWebServer()
        val childRequests = AtomicInteger()
        val mainRequests = AtomicInteger()
        val parentId = Uuid.random()
        val childId = Uuid.random()
        val model = Model(modelId = "agent-runtime-test", displayName = "Local test", abilities = listOf(ModelAbility.TOOL))
        val main = Assistant(name = "Runtime acceptance", chatModelId = model.id, streamOutput = false,
            capabilities = setOf("agents"), systemPrompt = "Manage a research assistant and return its result.")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val names = body["tools"]?.jsonArray.orEmpty().map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
                fun content(text: String) = response(buildJsonObject { put("role", "assistant"); put("content", text) })
                fun tool(id: String, name: String, args: JsonObject) = response(buildJsonObject {
                    put("role", "assistant"); put("content", JsonNull)
                    put("tool_calls", buildJsonArray { add(buildJsonObject {
                        put("id", id); put("type", "function"); put("function", buildJsonObject { put("name", name); put("arguments", args.toString()) })
                    }) })
                })
                if ("agent_run" !in names) {
                    assertFalse(names.contains("monthly_spending_summary"))
                    assertFalse(names.contains("agent_config"))
                    return if (childRequests.incrementAndGet() == 1) tool("child-question", "ask_user", buildJsonObject {
                        put("questions", buildJsonArray { add(buildJsonObject { put("id", "year"); put("question", "Which year?") }) })
                    }) else {
                        assertTrue(body["messages"]!!.jsonArray.any {
                            val message = it.jsonObject
                            message["tool_call_id"]?.jsonPrimitive?.content == "child-question" &&
                                message["content"]?.jsonPrimitive?.content == "2026"
                        })
                        content("Child verified year 2026")
                    }
                }
                val results = body["messages"]!!.jsonArray.map { it.jsonObject }
                    .filter { it["role"]?.jsonPrimitive?.content == "tool" }
                    .mapNotNull { runCatching { Json.parseToJsonElement(it["content"]!!.jsonPrimitive.content).jsonObject }.getOrNull() }
                return when (mainRequests.incrementAndGet()) {
                    1 -> tool("create-child", "workspace_write_file", buildJsonObject {
                        assertFalse(names.contains("agent_config"))
                        assertFalse(names.contains("agent_catalog"))
                        assertFalse(names.contains("agent_skill"))
                        put("path", "/agents/$childId/config.json")
                        put("text", """{"name":"Research acceptance","capabilities":["javascript"]}""")
                        put("overwrite", false)
                    })
                    2 -> tool("read-child-prompt", "workspace_read_file", buildJsonObject {
                        put("path", "/agents/$childId/AGENT.md")
                    })
                    3 -> tool("write-child-prompt", "workspace_write_file", buildJsonObject {
                        put("path", "/agents/$childId/AGENT.md"); put("text", "Ask which year, then report the year.")
                    })
                    4 -> tool("delegate-child", "agent_run", buildJsonObject {
                        put("action", "start"); put("request_id", "acceptance-start")
                        put("agent_id", childId.toString())
                        put("instruction", "Research the requested year.")
                    })
                    5 -> tool("parent-question", "ask_user", buildJsonObject {
                        put("questions", buildJsonArray { add(buildJsonObject { put("id", "year"); put("question", "Which year?") }) })
                    })
                    6 -> {
                        val waiting = results.first { it["status"]?.jsonPrimitive?.content == "WAITING_FOR_INPUT" }
                        tool("answer-child", "agent_run", buildJsonObject {
                            put("action", "answer"); put("run_id", waiting["run_id"]!!.jsonPrimitive.content)
                            put("request_id", "acceptance-answer"); put("if_revision", waiting["revision"]!!)
                            put("question_id", "child-question"); put("instruction", "2026")
                        })
                    }
                    else -> content("Completed: Child verified year 2026")
                }
            }
        }
        server.start()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, me.rerere.rikkahub.RouteActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        try {
            store.update { it.copy(assistants = listOf(main), assistantId = main.id, chatModelId = model.id,
                providers = listOf(ProviderSetting.OpenAI(name = "Local acceptance", apiKey = "local-test-only",
                    baseUrl = server.url("/v1").toString().trimEnd('/'), models = listOf(model))), enableSuggestion = false) }
            chat.saveConversation(parentId, Conversation.ofId(parentId, main.id).copy(title = "Agent acceptance"))
            chat.sendMessage(parentId, listOf(UIMessagePart.Text("Create a research assistant and delegate a task.")))
            withTimeout(120_000) {
                while (chat.getConversationFlow(parentId).value.currentMessages.none { message ->
                    message.parts.filterIsInstance<UIMessagePart.Tool>().any { it.toolCallId == "parent-question" && it.isPending }
                }) delay(100)
            }
            chat.handleToolApproval(parentId, "parent-question", approved = true, answer = "2026")
            withTimeout(120_000) {
                while (!chat.getConversationFlow(parentId).value.currentMessages.last().parts
                    .filterIsInstance<UIMessagePart.Text>().any { it.text.contains("Completed: Child verified year 2026") }) delay(100)
            }
            val runs = database.agentRunDao().list(parentId.toString())
            assertEquals(1, runs.size)
            assertEquals("COMPLETED", runs.single().status)
            assertEquals(2, childRequests.get())
            assertEquals(7, mainRequests.get())
            val configured = store.settingsFlowRaw.first().assistants.single { it.managedBy == main.id }
            assertEquals(setOf("javascript"), configured.capabilities)
            assertEquals("Ask which year, then report the year.", configured.systemPrompt)
            assertNotEquals(parentId.toString(), runs.single().id)
        } finally {
            chat.stopGeneration(parentId)
            store.restore(original)
            server.shutdown()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun selfPromptEditAppliesOnNextTurnAndKeepsActiveRunFrozen() = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val chat = GlobalContext.get().get<ChatService>()
        val original = store.settingsFlowRaw.first()
        val model = Model(modelId = "prompt-test", abilities = listOf(ModelAbility.TOOL))
        val agent = Assistant(name = "Prompt acceptance", chatModelId = model.id, streamOutput = false,
            capabilities = setOf("agents"), systemPrompt = "PROMPT_BEFORE_123")
        val id = Uuid.random()
        val server = MockWebServer()
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val index = requests.incrementAndGet()
                val instructions = body["messages"]!!.jsonArray.filter {
                    it.jsonObject["role"]!!.jsonPrimitive.content in setOf("system", "developer")
                }.toString()
                assertTrue(instructions, instructions.contains(if (index <= 2) "PROMPT_BEFORE_123" else "PROMPT_AFTER_456"))
                return response(buildJsonObject {
                    put("role", "assistant")
                    if (index == 1) {
                        put("content", JsonNull)
                        put("tool_calls", buildJsonArray { add(buildJsonObject {
                            put("id", "edit-self"); put("type", "function")
                            put("function", buildJsonObject {
                                put("name", "workspace_edit_file")
                                put("arguments", """{"path":"/agents/self/AGENT.md","old_text":"PROMPT_BEFORE_123","new_text":"PROMPT_AFTER_456"}""")
                            })
                        }) })
                    } else put("content", if (index == 2) "Prompt saved" else "New prompt active")
                })
            }
        }
        server.start()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, me.rerere.rikkahub.RouteActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        try {
            store.update { it.copy(assistants = listOf(agent), assistantId = agent.id, chatModelId = model.id,
                providers = listOf(ProviderSetting.OpenAI(name = "Local prompt test", apiKey = "local-test-only",
                    baseUrl = server.url("/v1").toString().trimEnd('/'), models = listOf(model))), enableSuggestion = false) }
            chat.saveConversation(id, Conversation.ofId(id, agent.id).copy(title = "Prompt acceptance"))
            suspend fun awaitText(text: String) = withTimeout(60_000) {
                while (chat.getConversationFlow(id).value.currentMessages.lastOrNull()?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()?.any { it.text == text } != true) delay(100)
                chat.getGenerationJobStateFlow(id).first { it == null }
            }
            chat.sendMessage(id, listOf(UIMessagePart.Text("Update your prompt.")))
            awaitText("Prompt saved")
            assertEquals("PROMPT_AFTER_456", chat.getConversationFlow(id).value.userPromptSnapshot?.content)
            chat.sendMessage(id, listOf(UIMessagePart.Text("Use the new prompt.")))
            awaitText("New prompt active")
            assertEquals(3, requests.get())
        } finally {
            chat.stopGeneration(id); store.restore(original); server.shutdown()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun response(message: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        buildJsonObject { put("id", "test-response"); put("object", "chat.completion"); put("created", 1)
            put("model", "agent-runtime-test")
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("message", message); put("finish_reason", if ("tool_calls" in message) "tool_calls" else "stop")
            }) })
            put("usage", buildJsonObject { put("prompt_tokens", 1); put("completion_tokens", 1); put("total_tokens", 2) })
        }.toString(),
    )
}
