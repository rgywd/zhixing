package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemoryState
import me.rerere.rikkahub.data.model.ProfileDimensions
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate

@Serializable
private data class MemoryToolResult(
    val id: Int,
    val kind: MemoryKind,
    val state: MemoryState,
    val content: String,
    val dimensionId: String,
)

private fun AssistantMemory.toToolResult() = MemoryToolResult(
    id = id,
    kind = kind,
    state = state,
    content = content,
    dimensionId = dimensionId,
)

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (MemoryKind, String, String) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory,
    onStateChange: suspend (Int, MemoryState) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            This is the single model-facing memory capability for long-term information across conversations.
            - No relevant record: `create` + `kind` + `content`
            - Existing relevant record: `edit` + `id` + `content`
            - No longer active but worth retaining: `archive` + `id`
            - User asks to reactivate an archived record: `restore` + `id`
            - User explicitly asks to forget/delete permanently: `delete` + `id`
            Archive, restore, and delete execute directly after the user asks for them.
            Memories are retrieved automatically in later conversations; do not ask for separate memory tools.
            `PROFILE` is a user-directed profile entry. Create or edit it only when the user explicitly asks
            to remember a durable fact/preference or corrects an existing profile. Ordinary conversation is
            handled by the separate longitudinal profile pipeline; never persist your own inference here.
            `PROFILE` is global across assistants. Creating one requires exactly one built-in `dimensionId`.
            `CONTEXT` is for everything else the user explicitly asks to remember. Do not invent task,
            calendar, contact, or other domain-specific workflows. Never provide `dimensionId` for `CONTEXT`.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            If the user explicitly asks to remember something, persist it and briefly confirm.
            Never create memory merely because a statement might be useful later.
            Today is ${LocalDate.now().toLocalString(true)}.
            Store one independently correctable fact per record.
            Similar or corrected memories must update the existing record instead of creating contradictions.

            Examples:
            {"action":"create","kind":"PROFILE","dimensionId":"preferences_values",
             "content":"User prefers Chinese replies."}
            {"action":"create","kind":"CONTEXT","content":"User plans to meet Zhang San tomorrow at 15:00."}
            {"action":"edit","id":12,"content":"User’s preferred name updated to “A-Xing”, prefers Chinese replies."}
            {"action":"archive","id":7}
            {"action":"delete","id":7}
        """.trimIndent(),
        needsApproval = { input ->
            val action = (input as? JsonObject)
                ?.get("action")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
            action in setOf("archive", "restore", "delete")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("archive")
                                add("restore")
                                add("delete")
                            }
                        )
                        put("description", "Operation to perform: create, edit, archive, restore, or delete")
                    })
                    put("kind", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(MemoryKind.PROFILE.name)
                            add(MemoryKind.CONTEXT.name)
                        })
                        put("description", "Memory kind, required for create: PROFILE or CONTEXT")
                    })
                    put("dimensionId", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            ProfileDimensions.builtIn.forEach { add(it) }
                        })
                        put(
                            "description",
                            "Built-in profile dimension. Required for PROFILE create; must be omitted for CONTEXT."
                        )
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val kindValue = params["kind"]?.jsonPrimitive?.contentOrNull ?: error("kind is required")
                    val kind = runCatching { MemoryKind.valueOf(kindValue) }
                        .getOrElse { error("unknown kind: $kindValue, must be one of [PROFILE, CONTEXT]") }
                    require(kind == MemoryKind.PROFILE || kind == MemoryKind.CONTEXT) {
                        "unknown kind: $kindValue, must be one of [PROFILE, CONTEXT]"
                    }
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    require(content.isNotBlank()) { "content must not be blank" }
                    val dimensionId = when (kind) {
                        MemoryKind.PROFILE -> {
                            val value = params["dimensionId"]?.jsonPrimitive?.contentOrNull
                                ?: error("dimensionId is required when kind is PROFILE")
                            require(value in ProfileDimensions.builtIn) {
                                "unknown dimensionId: $value, must be a built-in profile dimension"
                            }
                            value
                        }

                        MemoryKind.CONTEXT -> {
                            require("dimensionId" !in params) {
                                "dimensionId must be omitted when kind is CONTEXT"
                            }
                            ""
                        }

                        MemoryKind.OBSERVATION -> error(
                            "unknown kind: $kindValue, must be one of [PROFILE, CONTEXT]"
                        )
                    }
                    json.encodeToJsonElement(
                        MemoryToolResult.serializer(),
                        onCreation(kind, content, dimensionId).toToolResult(),
                    )
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    require(content.isNotBlank()) { "content must not be blank" }
                    json.encodeToJsonElement(
                        MemoryToolResult.serializer(),
                        onUpdate(id, content).toToolResult(),
                    )
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                "archive", "restore" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val state = if (action == "archive") MemoryState.ARCHIVED else MemoryState.ACTIVE
                    json.encodeToJsonElement(
                        MemoryToolResult.serializer(),
                        onStateChange(id, state).toToolResult(),
                    )
                }

                else -> error("unknown action: $action, must be one of [create, edit, archive, restore, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
