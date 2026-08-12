package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionException
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.memory.MEMORY_DOCUMENT_SOURCE_QUOTE_LIMIT
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

internal fun bindMemoryDocumentChatSources(
    sources: List<MemoryDocumentSource>,
    conversationId: String,
    messages: List<UIMessage>,
): List<MemoryDocumentSource> {
    if (sources.isEmpty()) throw ToolExecutionException("MEMORY_SOURCE_INVALID")
    val userMessages = messages.filter { it.role == MessageRole.USER }
    return sources.map { source ->
        if (source.type != MemoryDocumentSourceType.CHAT) {
            throw ToolExecutionException("MEMORY_SOURCE_INVALID")
        }
        val quote = source.quote.trim()
        if (quote.length !in 2..MEMORY_DOCUMENT_SOURCE_QUOTE_LIMIT) {
            throw ToolExecutionException("MEMORY_SOURCE_INVALID")
        }
        val message = userMessages.asReversed().firstOrNull { candidate ->
            candidate.parts.filterIsInstance<UIMessagePart.Text>().any { part -> part.text.contains(quote) }
        } ?: throw ToolExecutionException("MEMORY_SOURCE_INVALID")
        source.copy(
            conversationId = conversationId,
            messageId = message.id.toString(),
            quote = quote,
            observedAt = System.currentTimeMillis(),
        )
    }.distinctBy { it.messageId to it.quote }
}

fun buildMemoryDocumentTools(
    json: Json,
    checkCanFinalize: suspend () -> Unit = {},
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
            include an exact quote from a current user message. Supply only the quote in each source; the app binds
            its current conversation and message IDs. Never persist inference or sensitive information.
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
                                put("quote", buildJsonObject { put("type", "string") })
                            })
                            put("required", buildJsonArray {
                                add("quote")
                            })
                        })
                    })
                },
                required = emptyList(),
            )
        },
        execute = { input ->
            checkCanFinalize()
            val params = input as? JsonObject ?: throw ToolExecutionException("MEMORY_INPUT_INVALID")
            val action = params.optionalString("action")
            if (action == null) {
                onFinalize()
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("changed", false)
                        }.toString()
                    )
                )
            }
            val path = params.requiredString("path")
            val ifVersion = params.requiredLong("if_version")
            val sources = if (action == "delete") emptyList() else params.requireSources()
            val document = try {
                when (action) {
                    "write" -> onWrite(
                        path,
                        ifVersion,
                        params.requiredString("name"),
                        params.requiredString("description"),
                        params.stringListOrEmpty("aliases"),
                        params.requiredString("content"),
                        sources,
                    )

                    "str_replace" -> onReplace(
                        path,
                        ifVersion,
                        params.requiredString("old_text"),
                        params.requiredString("new_text"),
                        sources,
                    )

                    "append" -> onAppend(
                        path,
                        ifVersion,
                        params.requiredString("content"),
                        sources,
                    )

                    "delete" -> {
                        onDelete(path, ifVersion)
                        null
                    }

                    else -> throw ToolExecutionException("MEMORY_INPUT_INVALID")
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
            } catch (error: ToolExecutionException) {
                throw error
            } catch (_: IllegalArgumentException) {
                throw ToolExecutionException("MEMORY_WRITE_REJECTED")
            } catch (_: IllegalStateException) {
                throw ToolExecutionException("MEMORY_WRITE_REJECTED")
            }
            onFinalize()
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
    val array = this["sources"] as? JsonArray ?: throw ToolExecutionException("MEMORY_SOURCE_INVALID")
    return array.map { element ->
        val source = element as? JsonObject ?: throw ToolExecutionException("MEMORY_SOURCE_INVALID")
        MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            quote = source.requiredString("quote", "MEMORY_SOURCE_INVALID"),
        )
    }
}

private fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    return (value as? JsonPrimitive)?.contentOrNull
        ?: throw ToolExecutionException("MEMORY_INPUT_INVALID")
}

private fun JsonObject.requiredString(name: String, errorCode: String = "MEMORY_INPUT_INVALID"): String =
    (this[name] as? JsonPrimitive)?.contentOrNull ?: throw ToolExecutionException(errorCode)

private fun JsonObject.requiredLong(name: String): Long =
    (this[name] as? JsonPrimitive)?.longOrNull ?: throw ToolExecutionException("MEMORY_INPUT_INVALID")

private fun JsonObject.stringListOrEmpty(name: String): List<String> {
    val values = this[name] ?: return emptyList()
    val array = values as? JsonArray ?: throw ToolExecutionException("MEMORY_INPUT_INVALID")
    return array.map { value ->
        (value as? JsonPrimitive)?.contentOrNull ?: throw ToolExecutionException("MEMORY_INPUT_INVALID")
    }
}
