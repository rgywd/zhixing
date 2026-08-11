package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MemoryDocument
import me.rerere.rikkahub.data.model.MemoryDocumentSource
import me.rerere.rikkahub.data.model.MemoryDocumentSourceType
import me.rerere.rikkahub.data.repository.MemoryDocumentConflictException
import me.rerere.rikkahub.data.ai.renderMemoryDocumentMarkdown

@Serializable
private data class MemoryDocumentToolResult(
    val path: String,
    val name: String,
    val description: String,
    val aliases: List<String>,
    val content: String,
    val sources: List<MemoryDocumentSource>,
    val markdown: String,
    val version: Long,
)

private fun MemoryDocument.toToolResult() = MemoryDocumentToolResult(
    path = path,
    name = name,
    description = description,
    aliases = aliases,
    content = content,
    sources = sources,
    markdown = renderMemoryDocumentMarkdown(this),
    version = version,
)

internal fun validateMemoryDocumentChatSources(
    sources: List<MemoryDocumentSource>,
    conversationId: String,
    messages: List<UIMessage>,
): List<MemoryDocumentSource> {
    require(sources.isNotEmpty()) { "Memory writes require at least one exact user quote" }
    val userMessages = messages.filter { it.role == MessageRole.USER }.associateBy { it.id.toString() }
    return sources.map { source ->
        require(source.type == MemoryDocumentSourceType.CHAT) { "Model memory writes require CHAT sources" }
        require(source.conversationId == conversationId) { "Memory source conversation does not match current chat" }
        val message = userMessages[source.messageId]
            ?: error("Memory source message ${source.messageId} is not a current user message")
        val quote = source.quote.trim()
        require(quote.length >= 2) { "Memory source quote is too short" }
        require(
            message.parts.filterIsInstance<UIMessagePart.Text>().any { part -> part.text.contains(quote) }
        ) { "Memory source quote is not an exact substring of the user message" }
        source.copy(quote = quote, observedAt = System.currentTimeMillis())
    }.distinctBy { it.messageId to it.quote }
}

fun buildMemoryDocumentTools(
    json: Json,
    onFinalize: suspend () -> Unit = {},
    onRead: suspend (String) -> MemoryDocument,
    onWrite: suspend (
        path: String,
        ifVersion: Long,
        name: String,
        description: String,
        aliases: List<String>,
        content: String,
        sources: List<MemoryDocumentSource>,
    ) -> MemoryDocument,
    onReplace: suspend (
        path: String,
        ifVersion: Long,
        oldText: String,
        newText: String,
        sources: List<MemoryDocumentSource>,
    ) -> MemoryDocument,
    onAppend: suspend (
        path: String,
        ifVersion: Long,
        content: String,
        sources: List<MemoryDocumentSource>,
    ) -> MemoryDocument,
    onDelete: suspend (path: String, ifVersion: Long) -> Unit,
): List<Tool> = listOf(
    Tool(
        name = "memory_read",
        description = """
            Read one curated memory document by exact path. The system prompt contains only a listing plus
            /profile.md and /preferences.md; read /areas, /topics, or /people before using their contents.
            This never searches raw chat history; use conversation_search for that separate capability.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Exact path from the memory listing")
                    })
                },
                required = listOf("path"),
            )
        },
        execute = { input ->
            val path = input.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
            val payload = json.encodeToJsonElement(
                MemoryDocumentToolResult.serializer(),
                onRead(path).toToolResult(),
            )
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
    Tool(
        name = "memory_write",
        description = """
            Call this exactly once at the end of every successful chat run, immediately before the final answer.
            Proactively persist durable, useful facts explicitly stated by the user in current USER messages; a
            special "remember" phrase is not required. Omit action when no file should change. Do not persist
            transient requests, duplicates, inference, or sensitive information. Corrections may update a document.
            Supported actions: write, str_replace, append, delete. Every operation requires if_version; use 0 only
            when creating a new document. Every added fact must be a Markdown bullet beginning `- [stated] ` and
            include an exact quote from a current user message. Never persist inference or sensitive information.
            Delete still requires an explicit user request. This visible tool call is the only run-finalization step,
            not a background memory service, and it never changes raw conversation history.
        """.trimIndent(),
        needsApproval = { input ->
            val action = (input as? JsonObject)?.get("action") as? JsonPrimitive
            action?.contentOrNull == "delete"
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("write")
                            add("str_replace")
                            add("append")
                            add("delete")
                        })
                    })
                    put("path", buildJsonObject { put("type", "string") })
                    put("if_version", buildJsonObject { put("type", "integer") })
                    put("name", buildJsonObject { put("type", "string") })
                    put("description", buildJsonObject { put("type", "string") })
                    put("aliases", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("content", buildJsonObject { put("type", "string") })
                    put("old_text", buildJsonObject { put("type", "string") })
                    put("new_text", buildJsonObject { put("type", "string") })
                    put("sources", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("conversationId", buildJsonObject { put("type", "string") })
                                put("messageId", buildJsonObject { put("type", "string") })
                                put("quote", buildJsonObject { put("type", "string") })
                            })
                            put("required", buildJsonArray {
                                add("conversationId")
                                add("messageId")
                                add("quote")
                            })
                        })
                    })
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            onFinalize()
            val params = input.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull
            if (action == null) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("changed", false)
                        }.toString()
                    )
                )
            }
            val path = params["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
            val ifVersion = params["if_version"]?.jsonPrimitive?.longOrNull ?: error("if_version is required")
            val sources = if (action == "delete") emptyList() else params.requireSources()
            val document = try {
                when (action) {
                    "write" -> onWrite(
                        path,
                        ifVersion,
                        params["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required"),
                        params["description"]?.jsonPrimitive?.contentOrNull ?: error("description is required"),
                        params["aliases"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                        params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required"),
                        sources,
                    )

                    "str_replace" -> onReplace(
                        path,
                        ifVersion,
                        params["old_text"]?.jsonPrimitive?.contentOrNull ?: error("old_text is required"),
                        params["new_text"]?.jsonPrimitive?.contentOrNull ?: error("new_text is required"),
                        sources,
                    )

                    "append" -> onAppend(
                        path,
                        ifVersion,
                        params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required"),
                        sources,
                    )

                    "delete" -> {
                        onDelete(path, ifVersion)
                        null
                    }

                    else -> error("unknown action: $action")
                }
            } catch (conflict: MemoryDocumentConflictException) {
                val payload = buildJsonObject {
                    put("success", false)
                    put("error", "MEMORY_VERSION_CONFLICT")
                    put("message", conflict.message.orEmpty())
                    conflict.current?.let { current ->
                        put(
                            "current",
                            json.encodeToJsonElement(
                                MemoryDocumentToolResult.serializer(),
                                current.toToolResult(),
                            )
                        )
                    }
                }
                return@Tool listOf(UIMessagePart.Text(payload.toString()))
            }
            val payload = if (document == null) {
                buildJsonObject {
                    put("success", true)
                    put("changed", true)
                    put("path", path)
                }
            } else {
                json.encodeToJsonElement(MemoryDocumentToolResult.serializer(), document.toToolResult())
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
)

private fun JsonObject.requireSources(): List<MemoryDocumentSource> {
    val array = this["sources"]?.jsonArray ?: error("sources is required")
    return array.map { element ->
        val source = element.jsonObject
        MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = source["conversationId"]?.jsonPrimitive?.contentOrNull
                ?: error("source conversationId is required"),
            messageId = source["messageId"]?.jsonPrimitive?.contentOrNull
                ?: error("source messageId is required"),
            quote = source["quote"]?.jsonPrimitive?.contentOrNull ?: error("source quote is required"),
        )
    }
}
