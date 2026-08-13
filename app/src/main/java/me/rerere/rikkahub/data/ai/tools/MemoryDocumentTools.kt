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
import me.rerere.rikkahub.data.memory.MEMORY_DOCUMENT_ALIAS_LIMIT
import me.rerere.rikkahub.data.memory.MEMORY_DOCUMENT_CONTENT_LIMIT
import me.rerere.rikkahub.data.memory.MEMORY_DOCUMENT_DESCRIPTION_LIMIT
import me.rerere.rikkahub.data.memory.MEMORY_DOCUMENT_SOURCE_LIMIT
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

private data class MemoryDocumentMutation(
    val path: String,
    val document: MemoryDocument?,
)

private data class MemoryWriteFailureGuidance(
    val retryable: Boolean,
    val correction: String,
    val finalized: Boolean = false,
)

internal fun bindMemoryDocumentChatSources(
    sources: List<MemoryDocumentSource>,
    conversationId: String,
    messages: List<UIMessage>,
): List<MemoryDocumentSource> {
    if (sources.isEmpty()) throw ToolExecutionException("MEMORY_SOURCE_REQUIRED")
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
            Finish each successful chat run with one successful terminal memory call immediately before the final
            answer. A failed call does not finalize the run. When the result has success=false and retryable=true,
            follow correction and retry with fixed arguments; stop calling after success=true.

            Choose exactly one action and omit fields not used by that action:
            - no_change: only action; use when current USER messages contain no new durable memory.
            - write: path, if_version, name, description, optional aliases, non-blank content, and sources.
            - str_replace: path, if_version, non-blank old_text, new_text (which may be empty), and sources.
            - append: path, if_version, non-blank content, and sources.
            - delete: path and if_version; only after an explicit user request.

            Use if_version=0 only when creating a document. Every added fact must be a Markdown bullet beginning
            `- [stated] `. Each source contains only an exact quote from a current USER message; the app binds its
            current conversation and message IDs. Never persist transient requests, duplicates, inference, sensitive
            information, or assistant/tool text. A special "remember" phrase is not required. This visible tool call
            is the only run-finalization step, not a background memory service.
            It never changes raw conversation history.
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
                        put(
                            "description",
                            "Required terminal decision: no_change, write, str_replace, append, or delete."
                        )
                        put("enum", buildJsonArray {
                            add("no_change")
                            add("write")
                            add("str_replace")
                            add("append")
                            add("delete")
                        })
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Required for every mutation; omit for no_change.")
                        put("minLength", 1)
                    })
                    put("if_version", buildJsonObject {
                        put("type", "integer")
                        put("description", "Required for every mutation; use 0 only for a new document.")
                        put("minimum", 0)
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Required only for write.")
                        put("minLength", 1)
                        put("maxLength", 80)
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Required only for write; concise document routing description.")
                        put("minLength", 1)
                        put("maxLength", MEMORY_DOCUMENT_DESCRIPTION_LIMIT)
                    })
                    put("aliases", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional only for write.")
                        put("maxItems", MEMORY_DOCUMENT_ALIAS_LIMIT)
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Required and non-blank for write or append; each fact starts - [stated].")
                        put("maxLength", MEMORY_DOCUMENT_CONTENT_LIMIT)
                    })
                    put("old_text", buildJsonObject {
                        put("type", "string")
                        put("description", "Required and non-blank only for str_replace; must match exactly once.")
                        put("minLength", 1)
                    })
                    put("new_text", buildJsonObject {
                        put("type", "string")
                        put("description", "Required only for str_replace; may be empty to remove old_text.")
                    })
                    put("sources", buildJsonObject {
                        put("type", "array")
                        put("description", "Required for write, str_replace, and append; omit for no_change/delete.")
                        put("minItems", 1)
                        put("maxItems", MEMORY_DOCUMENT_SOURCE_LIMIT)
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("quote", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Exact substring from a current USER text message.")
                                    put("minLength", 2)
                                    put("maxLength", MEMORY_DOCUMENT_SOURCE_QUOTE_LIMIT)
                                })
                            })
                            put("required", buildJsonArray {
                                add("quote")
                            })
                            put("additionalProperties", false)
                        })
                    })
                },
                required = listOf("action"),
                additionalProperties = false,
            )
        },
        execute = { input ->
            try {
                checkCanFinalize()
                val params = input as? JsonObject ?: throw ToolExecutionException("MEMORY_INPUT_INVALID")
                val action = params.requiredAction()
                if (action == "no_change") {
                    onFinalize()
                    return@Tool memoryWriteSuccess(json = json, changed = false)
                }

                val mutation = try {
                    when (action) {
                        "write" -> {
                            val code = "MEMORY_WRITE_INPUT_INVALID"
                            val path = params.requiredNonBlankString("path", code)
                            val ifVersion = params.requiredNonNegativeLong("if_version", code)
                            val name = params.requiredNonBlankString("name", code)
                            val description = params.requiredNonBlankString("description", code)
                            val content = params.requiredNonBlankString("content", code)
                            val sources = params.requireSources()
                            MemoryDocumentMutation(
                                path = path,
                                document = onWrite(
                                    path,
                                    ifVersion,
                                    name,
                                    description,
                                    params.stringListOrEmpty("aliases", code),
                                    content,
                                    sources,
                                ),
                            )
                        }

                        "str_replace" -> {
                            val code = "MEMORY_REPLACE_INPUT_INVALID"
                            val path = params.requiredNonBlankString("path", code)
                            val ifVersion = params.requiredNonNegativeLong("if_version", code)
                            val oldText = params.requiredNonBlankString("old_text", code)
                            val newText = params.requiredString("new_text", code)
                            val sources = params.requireSources()
                            MemoryDocumentMutation(
                                path = path,
                                document = onReplace(path, ifVersion, oldText, newText, sources),
                            )
                        }

                        "append" -> {
                            val code = "MEMORY_APPEND_INPUT_INVALID"
                            val path = params.requiredNonBlankString("path", code)
                            val ifVersion = params.requiredNonNegativeLong("if_version", code)
                            val content = params.requiredNonBlankString("content", code)
                            val sources = params.requireSources()
                            MemoryDocumentMutation(
                                path = path,
                                document = onAppend(path, ifVersion, content, sources),
                            )
                        }

                        "delete" -> {
                            val code = "MEMORY_DELETE_INPUT_INVALID"
                            val path = params.requiredNonBlankString("path", code)
                            val ifVersion = params.requiredNonNegativeLong("if_version", code)
                            onDelete(path, ifVersion)
                            MemoryDocumentMutation(path = path, document = null)
                        }

                        else -> throw ToolExecutionException("MEMORY_ACTION_INVALID")
                    }
                } catch (conflict: MemoryDocumentConflictException) {
                    return@Tool memoryWriteFailure(
                        json = json,
                        code = "MEMORY_VERSION_CONFLICT",
                        current = conflict.current,
                    )
                }
                onFinalize()
                memoryWriteSuccess(
                    json = json,
                    changed = true,
                    path = mutation.path,
                    document = mutation.document,
                )
            } catch (error: ToolExecutionException) {
                memoryWriteFailure(json = json, code = error.code)
            } catch (_: IllegalArgumentException) {
                memoryWriteFailure(json = json, code = "MEMORY_WRITE_REJECTED")
            } catch (_: IllegalStateException) {
                memoryWriteFailure(json = json, code = "MEMORY_WRITE_REJECTED")
            }
        },
    ),
)

private fun memoryWriteSuccess(
    json: Json,
    changed: Boolean,
    path: String? = null,
    document: MemoryDocument? = null,
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("success", true)
            put("changed", changed)
            put("finalized", true)
            if (document != null) {
                json.encodeToJsonElement(
                    MemoryDocumentToolResult.serializer(),
                    document.toToolResult(),
                ).jsonObject.forEach { (key, value) -> put(key, value) }
            } else if (path != null) {
                put("path", path)
            }
        }.toString()
    )
)

private fun memoryWriteFailure(
    json: Json,
    code: String,
    current: MemoryDocument? = null,
): List<UIMessagePart> {
    val guidance = memoryWriteFailureGuidance(code)
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("success", false)
                put("changed", false)
                put("finalized", guidance.finalized)
                put("retryable", guidance.retryable)
                put("error", code)
                put("correction", guidance.correction)
                current?.let { document ->
                    put(
                        "current",
                        json.encodeToJsonElement(
                            MemoryDocumentToolResult.serializer(),
                            document.toToolResult(),
                        )
                    )
                }
            }
            .toString()
        )
    )
}

private fun memoryWriteFailureGuidance(code: String): MemoryWriteFailureGuidance = when (code) {
    "MEMORY_ACTION_REQUIRED" -> MemoryWriteFailureGuidance(
        retryable = true,
        correction = "Set action explicitly. Use no_change with no other fields when nothing should be stored.",
    )
    "MEMORY_ACTION_INVALID" -> MemoryWriteFailureGuidance(
        retryable = true,
        correction = "Use exactly one supported action: no_change, write, str_replace, append, or delete.",
    )
    "MEMORY_WRITE_INPUT_INVALID" -> MemoryWriteFailureGuidance(
        retryable = true,
        correction = "write requires path, non-negative if_version, non-blank name, description and content, plus " +
            "one or more sources containing exact current USER quotes; aliases are optional.",
    )
    "MEMORY_REPLACE_INPUT_INVALID" -> MemoryWriteFailureGuidance(
        retryable = true,
        correction = "str_replace requires path, non-negative if_version, non-blank old_text, new_text (which may be " +
            "empty), and one or more sources containing exact current USER quotes.",
    )
    "MEMORY_APPEND_INPUT_INVALID" -> MemoryWriteFailureGuidance(
        retryable = true,
        correction = "append requires path, non-negative if_version, non-blank content, and one or more sources " +
            "containing exact current USER quotes. Omit name, description, aliases, old_text, and new_text.",
    )
    "MEMORY_DELETE_INPUT_INVALID" -> MemoryWriteFailureGuidance(
        retryable = true,
        correction = "delete requires only path and non-negative if_version, and is allowed only after an explicit " +
            "user request.",
    )
    "MEMORY_SOURCE_REQUIRED" -> MemoryWriteFailureGuidance(
        retryable = true,
        correction = "Provide 1-$MEMORY_DOCUMENT_SOURCE_LIMIT sources; each source is {quote: exact substring from a " +
            "current USER text message}.",
    )
    "MEMORY_SOURCE_INVALID" -> MemoryWriteFailureGuidance(
        retryable = true,
        correction = "Use only exact 2-$MEMORY_DOCUMENT_SOURCE_QUOTE_LIMIT character quotes from current USER text " +
            "messages. Do not quote assistant text, tool output, or inferred wording.",
    )
    "MEMORY_VERSION_CONFLICT" -> MemoryWriteFailureGuidance(
        retryable = true,
        correction = "Use current.version and the returned current document to rebuild the intended mutation, " +
            "then retry.",
    )
    "MEMORY_WRITE_REJECTED" -> MemoryWriteFailureGuidance(
        retryable = true,
        correction = "Correct the mutation and retry: use a writable path, current version, `[stated]` bullets, " +
            "valid non-sensitive content, and exact current USER sources.",
    )
    "MEMORY_CONTEXT_UNAVAILABLE" -> MemoryWriteFailureGuidance(
        retryable = false,
        correction = "This run has no persisted conversation context, so a sourced memory mutation cannot be retried.",
    )
    "MEMORY_ALREADY_FINALIZED" -> MemoryWriteFailureGuidance(
        retryable = false,
        finalized = true,
        correction = "A successful terminal memory call already completed this run; do not call memory_write again.",
    )
    else -> MemoryWriteFailureGuidance(
        retryable = true,
        correction = "Use the documented action-specific shape, correct the arguments, and retry.",
    )
}

private fun JsonObject.requireSources(): List<MemoryDocumentSource> {
    val array = this["sources"] as? JsonArray ?: throw ToolExecutionException("MEMORY_SOURCE_REQUIRED")
    if (array.isEmpty()) throw ToolExecutionException("MEMORY_SOURCE_REQUIRED")
    if (array.size > MEMORY_DOCUMENT_SOURCE_LIMIT) throw ToolExecutionException("MEMORY_SOURCE_INVALID")
    return array.map { element ->
        val source = element as? JsonObject ?: throw ToolExecutionException("MEMORY_SOURCE_INVALID")
        val quote = source.requiredString("quote", "MEMORY_SOURCE_INVALID").trim()
        if (quote.length !in 2..MEMORY_DOCUMENT_SOURCE_QUOTE_LIMIT) {
            throw ToolExecutionException("MEMORY_SOURCE_INVALID")
        }
        MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            quote = quote,
        )
    }
}

private fun JsonObject.requiredAction(): String {
    val value = this["action"] ?: throw ToolExecutionException("MEMORY_ACTION_REQUIRED")
    return (value as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: throw ToolExecutionException("MEMORY_ACTION_INVALID")
}

private fun JsonObject.requiredString(name: String, errorCode: String = "MEMORY_INPUT_INVALID"): String =
    (this[name] as? JsonPrimitive)?.contentOrNull ?: throw ToolExecutionException(errorCode)

private fun JsonObject.requiredNonBlankString(name: String, errorCode: String): String =
    requiredString(name, errorCode).takeIf { it.isNotBlank() } ?: throw ToolExecutionException(errorCode)

private fun JsonObject.requiredNonNegativeLong(name: String, errorCode: String): Long =
    (this[name] as? JsonPrimitive)?.longOrNull
        ?.takeIf { it >= 0 }
        ?: throw ToolExecutionException(errorCode)

private fun JsonObject.stringListOrEmpty(name: String, errorCode: String): List<String> {
    val values = this[name] ?: return emptyList()
    val array = values as? JsonArray ?: throw ToolExecutionException(errorCode)
    return array.map { value ->
        (value as? JsonPrimitive)?.contentOrNull ?: throw ToolExecutionException(errorCode)
    }
}
