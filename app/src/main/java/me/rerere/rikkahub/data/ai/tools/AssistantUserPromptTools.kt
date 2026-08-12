package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantUserPromptSource
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.AssistantUserPromptConflictException

suspend fun createAssistantUserPromptTools(
    assistant: Assistant,
    workspaceRepository: WorkspaceRepository,
): List<Tool> {
    if (assistant.userPromptSource != AssistantUserPromptSource.KNOWLEDGE_VAULT) return emptyList()
    val workspaceId = assistant.workspaceId?.toString() ?: return emptyList()
    workspaceRepository.getById(workspaceId) ?: return emptyList()
    if (!workspaceRepository.isKnowledgeVaultInitialized(workspaceId)) return emptyList()

    val assistantId = assistant.id.toString()
    return listOf(
        Tool(
            name = "assistant_user_prompt_read",
            description = "Read this assistant's user-editable prompt document from its fixed knowledge-vault path. This cannot read the app's internal system, safety, tool, memory, or runtime instructions. The document controls only conversations started after a change; the current conversation keeps its frozen prompt.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            executionMode = ToolExecutionMode.PARALLEL_READ_ONLY,
            execute = {
                val document = workspaceRepository.readAssistantUserPrompt(workspaceId, assistantId)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("exists", document != null)
                    document?.let {
                        put("path", it.path)
                        put("content", it.content)
                        put("revision", it.revision)
                    }
                    put("applies_to", "new_conversations_only")
                }.toString()))
            },
        ),
        Tool(
            name = "assistant_user_prompt_edit",
            description = "Replace this assistant's user-editable prompt at its fixed knowledge-vault path. Read it first and pass the returned revision as if_revision. Omit if_revision only when the document does not exist. An empty content value intentionally clears the user prompt. This cannot change internal system, safety, tool, memory, or runtime instructions, and it never changes the current conversation's frozen prompt.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Complete replacement content; empty is an explicit empty prompt")
                        })
                        put("if_revision", buildJsonObject {
                            put("type", "string")
                            put("description", "Revision returned by assistant_user_prompt_read; omit only to create a missing document")
                        })
                    },
                    required = listOf("content"),
                    additionalProperties = false,
                )
            },
            execute = { input ->
                val params = input.jsonObject
                val content = params["content"]?.jsonPrimitive?.contentOrNull
                    ?: error("content is required")
                val expectedRevision = params["if_revision"]?.jsonPrimitive?.contentOrNull
                try {
                    val document = workspaceRepository.writeAssistantUserPrompt(
                        id = workspaceId,
                        assistantId = assistantId,
                        content = content,
                        expectedRevision = expectedRevision,
                    )
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("updated", true)
                        put("path", document.path)
                        put("revision", document.revision)
                        put("applies_to", "new_conversations_only")
                    }.toString()))
                } catch (conflict: AssistantUserPromptConflictException) {
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("updated", false)
                        put("error", "revision_conflict")
                        conflict.current?.let {
                            put("path", it.path)
                            put("current_revision", it.revision)
                        }
                        put("next", "Read the prompt again before retrying; do not overwrite concurrent changes.")
                    }.toString()))
                }
            },
        ),
    )
}
