package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ToolExecutionException
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MemoryDocument
import me.rerere.rikkahub.data.model.MemoryDocumentSource
import me.rerere.rikkahub.data.model.MemoryDocumentSourceType
import me.rerere.rikkahub.data.repository.MemoryDocumentConflictException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDocumentToolsTest {
    @Test
    fun memoryAndRawHistoryRemainDifferentTools() {
        val tools = memoryTools()
        val readDescription = tools.first().description.replace(Regex("\\s+"), " ")
        val writeDescription = tools.last().description.replace(Regex("\\s+"), " ")
        assertEquals(listOf("memory_read", "memory_write"), tools.map { it.name })
        assertTrue(tools.first().description.contains("never searches"))
        assertTrue(tools.first().description.contains("raw chat history"))
        assertTrue(tools.first().description.contains("materially help answer"))
        assertTrue(tools.first().description.contains("general knowledge"))
        assertTrue(readDescription.contains("one document per call"))
        assertTrue(readDescription.contains("Multiple sequential calls"))
        assertTrue(readDescription.contains("directly relevant relationship"))
        assertTrue(readDescription.contains("stop once you have enough"))
        assertTrue(tools.last().description.contains("never changes"))
        assertTrue(tools.last().description.contains("raw conversation history"))
        assertTrue(tools.last().description.contains("special \"remember\" phrase is not required"))
        assertTrue(tools.last().description.contains("app binds"))
        assertTrue(tools.last().description.contains("Call only when a real memory mutation is needed"))
        assertTrue(tools.last().description.contains("do not call this tool"))
        assertTrue(tools.last().description.contains("foreground chat run"))
        assertTrue(tools.last().description.contains("no background memory pass"))
        assertTrue(writeDescription.contains("one document per call"))
        assertTrue(writeDescription.contains("multiple calls"))
        assertTrue(writeDescription.contains("version returned by the preceding result"))
        assertFalse(tools.last().description.contains("no_change"))
        assertFalse(tools.last().description.contains("finalize"))
    }

    @Test
    fun onlyDeleteNeedsApproval() {
        val write = memoryTools().last()
        assertFalse(write.needsApproval(action("write")))
        assertFalse(write.needsApproval(action("append")))
        assertTrue(write.needsApproval(action("delete")))
    }

    @Test
    fun legacyNoChangeCallIsRejectedWithoutRequestingAnotherMemoryCall() = runBlocking {
        val result = memoryTools().last().execute(action("no_change")).single() as UIMessagePart.Text

        assertTrue(result.text.contains("\"success\":false"))
        assertTrue(result.text.contains("\"changed\":false"))
        assertTrue(result.text.contains("MEMORY_NO_CHANGE_UNSUPPORTED"))
        assertTrue(result.text.contains("\"retryable\":false"))
        assertFalse(result.text.contains("finalized"))
    }

    @Test
    fun memoryWriteSchemaRequiresAnExplicitActionAndDescribesSourceBounds() {
        val schema = memoryTools().last().parameters() as InputSchema.Obj
        val actionSchema = schema.properties.getValue("action").toString()
        val sourcesSchema = schema.properties.getValue("sources").toString()

        assertEquals(listOf("action"), schema.required)
        assertEquals(false, schema.additionalProperties)
        assertFalse(actionSchema.contains("no_change"))
        assertTrue(actionSchema.contains("write"))
        assertTrue(actionSchema.contains("str_replace"))
        assertTrue(actionSchema.contains("append"))
        assertTrue(actionSchema.contains("delete"))
        assertTrue(sourcesSchema.contains("quote"))
        assertTrue(sourcesSchema.contains("minItems"))
        assertTrue(sourcesSchema.contains("minLength"))
        assertFalse(sourcesSchema.contains("conversationId"))
        assertFalse(sourcesSchema.contains("messageId"))
    }

    @Test
    fun malformedAppendReturnsActionableErrorAndCanBeCorrected() = runBlocking {
        val tool = buildMemoryDocumentTools(
            json = Json,
            onRead = { document() },
            onWrite = { _, _, _, _, _, _, _ -> document() },
            onReplace = { _, _, _, _, _ -> document() },
            onAppend = { _, _, _, _ -> document() },
            onDelete = { _, _ -> },
        ).last()
        val malformed = buildJsonObject {
            put("action", "append")
            put("path", "/preferences.md")
            put("if_version", 1)
            put("name", "Preferences")
            put("description", "Stable response preferences")
            put("aliases", buildJsonArray {})
            put("content", "")
            put("old_text", "")
            put("new_text", "")
            put("sources", buildJsonArray {})
        }

        val failure = tool.execute(malformed).single() as UIMessagePart.Text
        assertTrue(failure.text.contains("\"success\":false"))
        assertTrue(failure.text.contains("MEMORY_APPEND_INPUT_INVALID"))
        assertTrue(failure.text.contains("\"retryable\":true"))
        assertFalse(failure.text.contains("finalized"))
        assertTrue(failure.text.contains("content"))
        assertTrue(failure.text.contains("sources"))

        val success = tool.execute(appendInput()).single() as UIMessagePart.Text
        assertTrue(success.text.contains("\"success\":true"))
        assertTrue(success.text.contains("\"changed\":true"))
        assertFalse(success.text.contains("finalized"))
    }

    @Test
    fun missingActionExplainsThatNoMutationNeedsNoToolCall() = runBlocking {
        val result = memoryTools().last().execute(buildJsonObject {}).single() as UIMessagePart.Text

        assertTrue(result.text.contains("MEMORY_ACTION_REQUIRED"))
        assertTrue(result.text.contains("do not call memory_write"))
        assertTrue(result.text.contains("\"retryable\":true"))
        assertFalse(result.text.contains("finalized"))
    }

    @Test
    fun failedWriteCanRetryWithoutTurningMemoryIntoRunFinalization() = runBlocking {
        var attempts = 0
        val tool = buildMemoryDocumentTools(
            json = Json,
            onRead = { document() },
            onWrite = { _, _, _, _, _, _, _ -> document() },
            onReplace = { _, _, _, _, _ -> document() },
            onAppend = { _, _, _, _ ->
                attempts += 1
                if (attempts == 1) error("simulated validation failure")
                document()
            },
            onDelete = { _, _ -> },
        ).last()

        val input = appendInput()
        val firstFailure = tool.execute(input).single() as UIMessagePart.Text
        assertTrue(firstFailure.text.contains("MEMORY_WRITE_REJECTED"))
        assertTrue(firstFailure.text.contains("\"retryable\":true"))
        assertFalse(firstFailure.text.contains("finalized"))

        val result = tool.execute(input).single() as UIMessagePart.Text
        assertTrue(result.text.contains("\"success\":true"))
        assertTrue(result.text.contains("\"version\":1"))
        assertFalse(result.text.contains("finalized"))

        val laterMutation = tool.execute(input).single() as UIMessagePart.Text
        assertTrue(laterMutation.text.contains("\"success\":true"))
        assertEquals(3, attempts)
    }

    @Test
    fun hostBindsChatSourceToTheLatestMatchingUserMessage() {
        val olderUser = UIMessage.user("我偏好中文回复")
        val latestUser = UIMessage.user("请记住我偏好中文回复")
        val source = MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = "model-supplied-conversation",
            messageId = olderUser.id.toString(),
            quote = "偏好中文回复",
        )

        val bound = bindMemoryDocumentChatSources(
            listOf(source),
            conversationId = "conversation-1",
            messages = listOf(olderUser, latestUser),
        )
        assertEquals("conversation-1", bound.single().conversationId)
        assertEquals(latestUser.id.toString(), bound.single().messageId)
        assertEquals("偏好中文回复", bound.single().quote)

        val unmatched = runCatching {
            bindMemoryDocumentChatSources(
                listOf(source.copy(quote = "模型推断的内容")),
                conversationId = "conversation-1",
                messages = listOf(latestUser, UIMessage.assistant("assistant reply")),
            )
        }.exceptionOrNull()
        assertEquals("MEMORY_SOURCE_INVALID", (unmatched as ToolExecutionException).code)
    }

    @Test
    fun versionConflictReturnsTheCurrentDocument() = runBlocking {
        val current = document().copy(content = "- [stated] 当前内容。", version = 4)
        var conflict = true
        val tool = buildMemoryDocumentTools(
            json = Json,
            onRead = { current },
            onWrite = { _, _, _, _, _, _, _ ->
                if (conflict) throw MemoryDocumentConflictException(current)
                current
            },
            onReplace = { _, _, _, _, _ -> current },
            onAppend = { _, _, _, _ -> current },
            onDelete = { _, _ -> },
        ).last()

        val input =
            buildJsonObject {
                put("action", "write")
                put("path", "/profile.md")
                put("if_version", 3)
                put("name", "Profile")
                put("description", "Stable profile")
                put("content", "- [stated] 旧内容。")
                put("sources", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("quote", "旧内容")
                    })
                })
            }
        val result = tool.execute(input).single() as UIMessagePart.Text

        assertTrue(result.text.contains("MEMORY_VERSION_CONFLICT"))
        assertTrue(result.text.contains("\"retryable\":true"))
        assertFalse(result.text.contains("finalized"))
        assertTrue(result.text.contains("\"version\":4"))
        assertTrue(result.text.contains("当前内容"))

        conflict = false
        val retried = tool.execute(input).single() as UIMessagePart.Text
        assertTrue(retried.text.contains("\"version\":4"))
        assertFalse(retried.text.contains("finalized"))
    }

    @Test
    fun readReturnsVirtualMarkdownWithFrontmatterAndStatedBody() = runBlocking {
        val source = MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = "conversation",
            messageId = "message",
            quote = "我偏好中文",
        )
        val tool = buildMemoryDocumentTools(
            json = Json,
            onRead = { document().copy(content = "- [stated] 我偏好中文。", sources = listOf(source)) },
            onWrite = { _, _, _, _, _, _, _ -> document() },
            onReplace = { _, _, _, _, _ -> document() },
            onAppend = { _, _, _, _ -> document() },
            onDelete = { _, _ -> },
        ).first()

        val result = tool.execute(buildJsonObject { put("path", "/profile.md") })
            .single() as UIMessagePart.Text
        assertTrue(result.text.contains("name: \\\"Profile\\\""))
        assertTrue(result.text.contains("sources:"))
        assertTrue(result.text.contains("[stated]"))
    }

    @Test
    fun multipleSequentialReadsCanFollowDirectlyRelatedDocuments() = runBlocking {
        val readPaths = mutableListOf<String>()
        val tool = buildMemoryDocumentTools(
            json = Json,
            onRead = { path ->
                readPaths += path
                when (path) {
                    "/people/alice.md" -> document(
                        path = path,
                        name = "Alice",
                        description = "Alice's durable work context",
                        content = "- [stated] Alice works on /areas/phoenix.md.",
                        version = 2,
                    )

                    "/areas/phoenix.md" -> document(
                        path = path,
                        name = "Phoenix",
                        description = "Phoenix project context",
                        content = "- [stated] Phoenix ships offline search.",
                        version = 5,
                    )

                    else -> error("unexpected path: $path")
                }
            },
            onWrite = { _, _, _, _, _, _, _ -> document() },
            onReplace = { _, _, _, _, _ -> document() },
            onAppend = { _, _, _, _ -> document() },
            onDelete = { _, _ -> },
        ).first()

        val person = tool.execute(buildJsonObject { put("path", "/people/alice.md") })
            .single() as UIMessagePart.Text
        val project = tool.execute(buildJsonObject { put("path", "/areas/phoenix.md") })
            .single() as UIMessagePart.Text

        assertEquals(listOf("/people/alice.md", "/areas/phoenix.md"), readPaths)
        assertTrue(person.text.contains("Alice works on /areas/phoenix.md"))
        assertTrue(project.text.contains("Phoenix ships offline search"))
    }

    @Test
    fun multipleWritesCanMutateDifferentDocumentsInOneRun() = runBlocking {
        val mutations = mutableListOf<Pair<String, Long>>()
        val tool = buildMemoryDocumentTools(
            json = Json,
            onRead = { document() },
            onWrite = { _, _, _, _, _, _, _ -> document() },
            onReplace = { _, _, _, _, _ -> document() },
            onAppend = { path, ifVersion, content, _ ->
                mutations += path to ifVersion
                document(
                    path = path,
                    name = path.substringAfterLast('/').substringBeforeLast('.'),
                    description = "Durable linked context",
                    content = content,
                    version = ifVersion + 1,
                )
            },
            onDelete = { _, _ -> },
        ).last()

        val person = tool.execute(
            appendInput(
                path = "/people/alice.md",
                ifVersion = 2,
                content = "- [stated] Alice owns Phoenix delivery.",
                quote = "Alice owns Phoenix",
            )
        ).single() as UIMessagePart.Text
        val project = tool.execute(
            appendInput(
                path = "/areas/phoenix.md",
                ifVersion = 7,
                content = "- [stated] Phoenix delivery is owned by Alice.",
                quote = "Phoenix delivery",
            )
        ).single() as UIMessagePart.Text

        assertEquals(
            listOf("/people/alice.md" to 2L, "/areas/phoenix.md" to 7L),
            mutations,
        )
        assertTrue(person.text.contains("/people/alice.md"))
        assertTrue(person.text.contains("\"version\":3"))
        assertTrue(project.text.contains("/areas/phoenix.md"))
        assertTrue(project.text.contains("\"version\":8"))
    }

    private fun memoryTools() = buildMemoryDocumentTools(
        json = Json,
        onRead = { document() },
        onWrite = { _, _, _, _, _, _, _ -> document() },
        onReplace = { _, _, _, _, _ -> document() },
        onAppend = { _, _, _, _ -> document() },
        onDelete = { _, _ -> },
    )

    private fun document(
        path: String = "/profile.md",
        name: String = "Profile",
        description: String = "Stable profile",
        content: String = "",
        version: Long = 1,
    ) = MemoryDocument(
        scopeId = "__global__",
        path = path,
        name = name,
        description = description,
        content = content,
        version = version,
    )

    private fun appendInput(
        path: String = "/profile.md",
        ifVersion: Long = 1,
        content: String = "- [stated] 用户偏好中文回复。",
        quote: String = "偏好中文回复",
    ) = buildJsonObject {
        put("action", "append")
        put("path", path)
        put("if_version", ifVersion)
        put("content", content)
        put("sources", buildJsonArray {
            add(buildJsonObject { put("quote", quote) })
        })
    }

    private fun action(value: String) = buildJsonObject { put("action", value) }
}
