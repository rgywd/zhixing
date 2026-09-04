package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionException
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
    currentConversationId: Uuid? = null,
): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = """
            List the user's recent conversations with you to understand their preferences and ongoing topics.
            Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated.
            Use this when you need quick context about what the user has been discussing lately.
            Only titles and dates are returned; use `conversation_search` to look up the actual content.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of recent conversations to return (default: 10, max: 30)"
                        )
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
            val recent = conversationRepo.getRecentConversations(
                assistantId = assistantId,
                limit = limit,
            )
            val payload = buildJsonArray {
                recent.forEach { conversation ->
                    add(buildJsonObject {
                        put("id", conversation.id.toString())
                        put("title", conversation.title.ifBlank { "Untitled" })
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_search",
        description = """
            Full-text search across the user's past conversations to recall specific information they mentioned before.
            Use focused keywords. Run multiple searches with different keywords if needed.
            Results are limited to selected USER messages from this assistant's other conversations. Each result includes
            a source_ref, the conversation title, a search-only snippet with matched keywords wrapped in [brackets],
            and the date. The snippet is not exact source text. Call conversation_read with source_ref before quoting or
            saving a result to memory.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to search for in past conversation messages")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of results to return (default: 15, max: 50)"
                        )
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                ?: error("query is required")
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
            val conversations = mutableMapOf<Uuid, Conversation>()
            val results = mutableListOf<Pair<MessageSearchResult, ResolvedConversationUserSource>>()
            for (result in conversationRepo.searchMessages(query, MessageSearchSort.RELEVANCE)) {
                if (results.size >= limit) break
                val sourceRef = runCatching {
                    ConversationSourceRef(
                        conversationId = Uuid.parse(result.conversationId),
                        nodeId = Uuid.parse(result.nodeId),
                        messageId = Uuid.parse(result.messageId),
                    )
                }.getOrNull() ?: continue
                val conversation = conversations[sourceRef.conversationId]
                    ?: conversationRepo.getConversationById(sourceRef.conversationId).also { loaded ->
                        if (loaded != null) conversations[sourceRef.conversationId] = loaded
                    }
                    ?: continue
                val resolved = runCatching {
                    resolveSelectedUserSource(
                        conversation = conversation,
                        sourceRef = sourceRef,
                        assistantId = assistantId,
                        excludedConversationId = currentConversationId,
                    )
                }.getOrNull() ?: continue
                results += result to resolved
            }
            val payload = buildJsonArray {
                results.forEach { (result, resolved) ->
                    add(buildJsonObject {
                        put("conversation_id", resolved.sourceRef.conversationId.toString())
                        put("source_ref", resolved.sourceRef.encode())
                        put("title", resolved.title.ifBlank { "Untitled" })
                        put("snippet", result.snippet)
                        put("date", resolved.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        },
        executionMode = ToolExecutionMode.PARALLEL_READ_ONLY,
        deduplicateWithinRun = true,
    ),
    Tool(
        name = "conversation_read",
        description = """
            Read the exact USER text behind one source_ref returned by conversation_search. Use this before quoting a
            historical message or passing {source_ref, quote} to memory_write. The source_ref is only a locator; the app
            revalidates assistant scope, selected branch, USER role, and message existence on every call.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("source_ref", buildJsonObject {
                        put("type", "string")
                        put("description", "Exact source_ref returned by conversation_search")
                        put("minLength", 1)
                    })
                },
                required = listOf("source_ref"),
                additionalProperties = false,
            )
        },
        execute = {
            val encodedSourceRef = it.jsonObject["source_ref"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: throw ToolExecutionException("MEMORY_SOURCE_REF_INVALID")
            val sourceRef = ConversationSourceRef.decode(encodedSourceRef)
            val conversation = conversationRepo.getConversationById(sourceRef.conversationId)
                ?: throw ToolExecutionException("MEMORY_SOURCE_NOT_FOUND")
            val resolved = resolveSelectedUserSource(
                conversation = conversation,
                sourceRef = sourceRef,
                assistantId = assistantId,
                excludedConversationId = currentConversationId,
            )
            val payload = buildJsonObject {
                put("source_ref", encodedSourceRef)
                put("conversation_id", sourceRef.conversationId.toString())
                put("title", resolved.title.ifBlank { "Untitled" })
                put("date", resolved.updateAt.toLocalDate())
                put("text_parts", buildJsonArray { resolved.textParts.forEach(::add) })
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        },
        executionMode = ToolExecutionMode.PARALLEL_READ_ONLY,
        deduplicateWithinRun = true,
    ),
)
