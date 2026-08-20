package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageSearchType
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ResponseAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI Response API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * ResponseAPI uses a different format than ChatCompletionsAPI:
 * - function_call items for tool invocations
 * - function_call_output items for tool results
 */
class ResponseAPIMessageTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    // Helper to invoke buildMessages method
    private fun invokeBuildMessages(messages: List<UIMessage>): JsonArray {
        return api.buildMessages(messages)
    }

    private fun invokeBuildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return api.buildRequestBody(providerSetting, listOf(UIMessage.user("hello")), params, stream)
    }

    private fun createReasoningParams(reasoningLevel: ReasoningLevel = ReasoningLevel.OFF): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = "test-model",
                displayName = "test-model",
                abilities = listOf(ModelAbility.REASONING)
            ),
            reasoningLevel = reasoningLevel
        )
    }

    @Test
    fun `multi-round tool calls should produce correct function_call and function_call_output pairs`() {
        // Scenario: Multiple tool calls in sequence
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result"),
                UIMessagePart.Text("Now calculating"),
                createExecutedTool("call_2", "calculate", """{"expr": "2+2"}""", "4"),
                UIMessagePart.Text("The answer is 4")
            )
        )

        val messages = listOf(
            UIMessage.user("Calculate something"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify structure for ResponseAPI:
        // 1. user message
        // 2. assistant content (text)
        // 3. function_call (search)
        // 4. function_call_output (search result)
        // 5. assistant content (text)
        // 6. function_call (calculate)
        // 7. function_call_output (calculate result)
        // 8. assistant content (final text)

        // Collect function_call items
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        assertEquals("Should have 2 function_call items", 2, functionCalls.size)

        // Collect function_call_output items
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }
        assertEquals("Should have 2 function_call_output items", 2, functionOutputs.size)

        // Verify first function_call
        val call1 = functionCalls[0].jsonObject
        assertEquals("call_1", call1["call_id"]?.jsonPrimitive?.content)
        assertEquals("search", call1["name"]?.jsonPrimitive?.content)

        // Verify first function_call_output
        val output1 = functionOutputs[0].jsonObject
        assertEquals("call_1", output1["call_id"]?.jsonPrimitive?.content)
        assertTrue(output1["output"]?.jsonPrimitive?.content?.contains("Search result") == true)

        // Verify second function_call
        val call2 = functionCalls[1].jsonObject
        assertEquals("call_2", call2["call_id"]?.jsonPrimitive?.content)
        assertEquals("calculate", call2["name"]?.jsonPrimitive?.content)

        // Verify second function_call_output
        val output2 = functionOutputs[1].jsonObject
        assertEquals("call_2", output2["call_id"]?.jsonPrimitive?.content)
        assertTrue(output2["output"]?.jsonPrimitive?.content?.contains("4") == true)
    }

    @Test
    fun `function_call should be immediately followed by function_call_output`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_abc", "my_tool", """{"x": 1}""", "result")
            )
        )

        val messages = listOf(
            UIMessage.user("Use tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find function_call index
        var functionCallIndex = -1
        for (i in result.indices) {
            if (result[i].jsonObject["type"]?.jsonPrimitive?.content == "function_call") {
                functionCallIndex = i
                break
            }
        }

        assertTrue("Should find function_call", functionCallIndex >= 0)
        assertTrue("function_call_output should follow", functionCallIndex < result.size - 1)

        val nextItem = result[functionCallIndex + 1].jsonObject
        assertEquals("function_call_output", nextItem["type"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextItem["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parallel tool calls should produce sequential function_call and output pairs`() {
        // Multiple tools called together
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running multiple tools"),
                createExecutedTool("call_1", "tool_a", "{}", "Result A"),
                createExecutedTool("call_2", "tool_b", "{}", "Result B"),
                createExecutedTool("call_3", "tool_c", "{}", "Result C"),
                UIMessagePart.Text("All done")
            )
        )

        val messages = listOf(
            UIMessage.user("Do things"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Should have 3 function_calls and 3 function_call_outputs
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals(3, functionCalls.size)
        assertEquals(3, functionOutputs.size)

        // Verify each function_call is followed by its output (in pairs)
        val callIds = listOf("call_1", "call_2", "call_3")
        for (callId in callIds) {
            var callIndex = -1
            var outputIndex = -1
            for (i in result.indices) {
                val item = result[i].jsonObject
                if (item["type"]?.jsonPrimitive?.content == "function_call" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    callIndex = i
                }
                if (item["type"]?.jsonPrimitive?.content == "function_call_output" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    outputIndex = i
                }
            }
            assertTrue("Should find function_call for $callId", callIndex >= 0)
            assertTrue("Should find function_call_output for $callId", outputIndex >= 0)
            assertEquals("Output should immediately follow call for $callId",
                callIndex + 1, outputIndex)
        }
    }

    @Test
    fun `content with text should be properly formatted`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Hello world"),
                createExecutedTool("call_1", "test", "{}", "output"),
                UIMessagePart.Text("Goodbye")
            )
        )

        val messages = listOf(
            UIMessage.user("Hi"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find assistant content messages
        val assistantContents = result.filter {
            val obj = it.jsonObject
            obj["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertTrue("Should have assistant content messages", assistantContents.isNotEmpty())

        // First assistant message should have "Hello world"
        val firstAssistant = assistantContents[0].jsonObject
        val content = firstAssistant["content"]
        val hasHello = when {
            content is kotlinx.serialization.json.JsonPrimitive -> content.content.contains("Hello")
            content is JsonArray -> content.any {
                it.jsonObject["text"]?.jsonPrimitive?.content?.contains("Hello") == true
            }
            else -> false
        }
        assertTrue("First assistant should contain 'Hello'", hasHello)
    }

    @Test
    fun `complex multi-round scenario with text and tools interleaved`() {
        val messages = listOf(
            UIMessage.user("Execute a complex task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Starting task"),
                    createExecutedTool("step1", "init", "{}", "initialized"),
                    UIMessagePart.Text("Processing..."),
                    createExecutedTool("step2", "process", """{"data": "test"}""", "processed"),
                    UIMessagePart.Text("Finalizing..."),
                    createExecutedTool("step3", "finalize", "{}", "done"),
                    UIMessagePart.Text("Task completed successfully")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Count items
        val userMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "user"
        }
        val assistantMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }
        val functionCalls = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals("Should have 1 user message", 1, userMessages)
        assertEquals("Should have 3 function_calls", 3, functionCalls)
        assertEquals("Should have 3 function_call_outputs", 3, functionOutputs)
        assertTrue("Should have multiple assistant messages", assistantMessages >= 1)

        // Verify the order: each function_call immediately followed by function_call_output
        var lastCallIndex = -1
        for (i in result.indices) {
            val item = result[i].jsonObject
            if (item["type"]?.jsonPrimitive?.content == "function_call") {
                assertTrue("function_call should not be last", i < result.size - 1)
                val next = result[i + 1].jsonObject
                assertEquals("function_call_output should follow",
                    "function_call_output", next["type"]?.jsonPrimitive?.content)
                assertTrue("call_id should match",
                    item["call_id"]?.jsonPrimitive?.content == next["call_id"]?.jsonPrimitive?.content)
                assertTrue("Order should be maintained", i > lastCallIndex)
                lastCallIndex = i
            }
        }
    }

    @Test
    fun `volc response api should not include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertFalse("volc should not include reasoning.summary", reasoning!!.containsKey("summary"))
    }

    @Test
    fun `openai response api should include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("auto", reasoning!!["summary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `volc response api should keep reasoning effort when non auto`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams(reasoningLevel = ReasoningLevel.LOW)
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("low", reasoning!!["effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Bailian request should keep function tools together with web search`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        )
        val model = Model(
            modelId = "qwen3.8-max",
            displayName = "qwen3.8-max",
            abilities = listOf(ModelAbility.TOOL),
            tools = setOf(BuiltInTools.Search),
        )
        val params = TextGenerationParams(
            model = model,
            tools = listOf(
                Tool(
                    name = "local_tool",
                    description = "A local function tool",
                    execute = { emptyList() },
                )
            )
        )

        val requestTools = invokeBuildRequestBody(providerSetting, params)["tools"]!!.jsonArray

        assertEquals(2, requestTools.size)
        assertEquals("function", requestTools[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("local_tool", requestTools[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("web_search", requestTools[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Bailian request should serialize selected Harness tools and extractor dependency`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        )
        val params = TextGenerationParams(
            model = Model(
                modelId = "qwen3.7-plus",
                displayName = "qwen3.7-plus",
                tools = setOf(
                    BuiltInTools.WebExtractor,
                    BuiltInTools.WebSearchImage,
                    BuiltInTools.ImageSearch,
                ),
            )
        )

        val requestTools = api.buildRequestBody(
            providerSetting,
            listOf(UIMessage.user("find an image")),
            params,
            false,
        )["tools"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }

        assertTrue("web_search" in requestTools)
        assertTrue("web_extractor" in requestTools)
        assertTrue("web_search_image" in requestTools)
        assertFalse("image_search" in requestTools)
    }

    @Test
    fun `Bailian image search should only be sent when conversation contains an image`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        )
        val params = TextGenerationParams(
            model = Model(
                modelId = "qwen3.7-plus",
                displayName = "qwen3.7-plus",
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                tools = setOf(BuiltInTools.ImageSearch),
            )
        )
        val messageWithImage = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("find similar images"),
                UIMessagePart.Image("https://example.com/input.png"),
            )
        )

        val requestTools = api.buildRequestBody(
            providerSetting,
            listOf(messageWithImage),
            params,
            false,
        )["tools"]!!.jsonArray

        assertEquals("image_search", requestTools.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Bailian response api should use DashScope thinking parameters`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = TextGenerationParams(
                model = Model(
                    modelId = "qwen3.8-max",
                    displayName = "qwen3.8-max",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                reasoningLevel = ReasoningLevel.OFF,
            )
        )

        assertEquals("false", requestBody["enable_thinking"]?.jsonPrimitive?.content)
        assertEquals("0", requestBody["thinking_budget"]?.jsonPrimitive?.content)
        assertFalse(requestBody.containsKey("reasoning"))
        assertFalse(requestBody.containsKey("include"))
    }

    @Test
    fun `web search sources should become deduplicated url citations`() {
        val outputs = webSearchOutputs()

        val citations = api.parseWebSearchCitations(outputs)

        assertEquals(2, citations.size)
        assertEquals("First source", citations[0].title)
        assertEquals("https://example.com/a", citations[0].url)
        assertEquals("example.org", citations[1].title)
    }

    @Test
    fun `web extractor urls should become safe url citations`() {
        val outputs = JsonArray(listOf(buildJsonObject {
            put("type", "web_extractor_call")
            put("urls", JsonArray(listOf(
                kotlinx.serialization.json.JsonPrimitive("https://docs.example.com/page"),
                kotlinx.serialization.json.JsonPrimitive("javascript:alert(1)"),
            )))
        }))

        val citations = api.parseWebSearchCitations(outputs)

        assertEquals(1, citations.size)
        assertEquals("docs.example.com", citations.single().title)
        assertEquals("https://docs.example.com/page", citations.single().url)
    }

    @Test
    fun `non-streaming response should expose web search sources on assistant message`() {
        val response = responseWithWebSearchSources()

        val message = api.parseResponseOutput(response).choices.single().message!!

        assertEquals("Answer", (message.parts.single() as UIMessagePart.Text).text)
        assertEquals(2, message.annotations.size)
    }

    @Test
    fun `stream completion should expose web search sources as annotation delta`() {
        val event = buildJsonObject {
            put("type", "response.completed")
            put("response", responseWithWebSearchSources())
        }

        val delta = api.parseResponseDelta(event)!!.choices.single().delta!!

        assertTrue(delta.parts.isEmpty())
        assertEquals(2, delta.annotations.size)
    }

    @Test
    fun `image search outputs should become validated deduplicated image citations`() {
        val citations = api.parseImageSearchCitations(imageSearchOutputs())

        assertEquals(2, citations.size)
        assertEquals("Text result", citations[0].title)
        assertEquals("https://images.example.com/text.png", citations[0].url)
        assertEquals(ImageSearchType.TEXT, citations[0].searchType)
        assertEquals(ImageSearchType.IMAGE, citations[1].searchType)
    }

    @Test
    fun `stream completion should expose image search results as annotation deltas`() {
        val event = buildJsonObject {
            put("type", "response.completed")
            put("response", buildJsonObject {
                put("id", "resp_images")
                put("model", "qwen3.7-plus")
                put("output", imageSearchOutputs())
            })
        }

        val delta = api.parseResponseDelta(event)!!.choices.single().delta!!
        val citations = delta.annotations.filterIsInstance<UIMessageAnnotation.ImageCitation>()

        assertTrue(delta.parts.isEmpty())
        assertEquals(2, citations.size)
    }

    @Test
    fun `openai response api should send max effort`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createReasoningParams(reasoningLevel = ReasoningLevel.MAX),
        )

        assertEquals(
            "max",
            requestBody["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `non Chat auto keeps provider automatic effort behavior`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createReasoningParams(reasoningLevel = ReasoningLevel.AUTO),
        )

        assertFalse(requestBody["reasoning"]?.jsonObject?.containsKey("effort") ?: true)
    }

    // ==================== Helper Functions ====================

    private fun responseWithWebSearchSources() = buildJsonObject {
        put("id", "resp_1")
        put("model", "qwen3.8-max")
        put("output", JsonArray(webSearchOutputs() + buildJsonObject {
            put("type", "message")
            put("content", JsonArray(listOf(buildJsonObject {
                put("type", "output_text")
                put("text", "Answer")
            })))
        }))
    }

    private fun webSearchOutputs() = JsonArray(
        listOf(
            buildJsonObject {
                put("type", "web_search_call")
                put("action", buildJsonObject {
                    put("sources", JsonArray(listOf(
                        buildJsonObject {
                            put("title", "First source")
                            put("url", "https://example.com/a")
                        },
                        buildJsonObject {
                            put("title", "Duplicate")
                            put("url", "https://example.com/a")
                        },
                        buildJsonObject {
                            put("url", "https://example.org/b")
                        },
                        buildJsonObject {
                            put("title", "Unsafe")
                            put("url", "javascript:alert(1)")
                        },
                    )))
                })
            }
        )
    )

    private fun imageSearchOutputs() = JsonArray(
        listOf(
            buildJsonObject {
                put("type", "web_search_image_call")
                put(
                    "output",
                    """[{"title":"Text result","url":"https://images.example.com/text.png","index":0},{"title":"Unsafe","url":"javascript:alert(1)","index":1}]"""
                )
            },
            buildJsonObject {
                put("type", "image_search_call")
                put(
                    "output",
                    """[{"title":"Duplicate","url":"https://images.example.com/text.png","index":0},{"title":"Similar result","url":"https://images.example.com/similar.png","index":1}]"""
                )
            },
        )
    )

    private fun createExecutedTool(
        callId: String,
        name: String,
        input: String,
        output: String
    ): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Text(output))
        )
    }
}
