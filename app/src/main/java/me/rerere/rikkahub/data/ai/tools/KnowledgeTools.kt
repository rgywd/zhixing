package me.rerere.rikkahub.data.ai.tools

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
import me.rerere.rikkahub.data.knowledge.KnowledgeSpaceService
import me.rerere.rikkahub.data.repository.WorkspaceRepository

suspend fun createKnowledgeTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    knowledgeSpaceService: KnowledgeSpaceService,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
    if (!workspaceRepository.isKnowledgeVaultInitialized(workspaceId)) return emptyList()
    val approvals = workspace.toolApprovalOverrides()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvals)

    return listOf(
        Tool(
            name = "knowledge_status",
            description = "Inspect the bound OrbitOS CN vault at /workspace/vault, including local content and searchable document counts. Works without Rootfs.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            needsApproval = { needsApproval("knowledge_status") },
            execute = {
                val status = workspaceRepository.knowledgeSpaceStatus(workspaceId)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("initialized", status.initialized)
                    put("contentRoot", status.contentRoot)
                    put("contentFileCount", status.contentFileCount)
                    // Keep the old field as an output alias for existing rendered tool history.
                    put("sourceCount", status.contentFileCount)
                    put("indexedDocumentCount", status.indexedDocumentCount)
                }.toString()))
            },
        ),
        Tool(
            name = "knowledge_search",
            description = "Search the bound OrbitOS CN vault only when the user asks to consult it or the task clearly depends on it. Covers inbox, daily notes, C.A.P. projects, reference research, atomic wiki concepts, curated resources, tool entries, plans, system notes, and vault-local skills. Do not use this as a default preflight. Returns line-level excerpts and source citations. Works without Rootfs.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("query", buildJsonObject { put("type", "string"); put("description", "Text to find") })
                        put("limit", buildJsonObject { put("type", "integer"); put("description", "Maximum matches, 1-50") })
                    },
                    required = listOf("query"),
                )
            },
            needsApproval = { needsApproval("knowledge_search") },
            execute = { input ->
                val params = input.jsonObject
                val query = params["query"]?.jsonPrimitive?.contentOrNull ?: error("query is required")
                val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 20
                val result = workspaceRepository.searchKnowledge(workspaceId, query, limit)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("query", result.query)
                    put("truncated", result.truncated)
                    put("matches", buildJsonArray {
                        result.matches.forEach { match ->
                            add(buildJsonObject {
                                put("path", match.path)
                                put("sourcePath", match.sourcePath)
                                put("line", match.line)
                                put("excerpt", match.excerpt)
                                put("citation", match.citation)
                            })
                        }
                    })
                }.toString()))
            },
        ),
        Tool(
            name = "knowledge_read",
            description = "Read a precise line range from a searchable vault file after knowledge_search found a relevant path. Credential and Git internals are outside this tool's readable boundary. Do not use it as a default preflight. Works without Rootfs.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "Knowledge path returned by search") })
                        put("start_line", buildJsonObject { put("type", "integer"); put("description", "First line, defaults to 1") })
                        put("end_line", buildJsonObject { put("type", "integer"); put("description", "Last line, at most 200 lines") })
                    },
                    required = listOf("path"),
                )
            },
            needsApproval = { needsApproval("knowledge_read") },
            execute = { input ->
                val params = input.jsonObject
                val path = params["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
                val start = params["start_line"]?.jsonPrimitive?.intOrNull ?: 1
                val end = params["end_line"]?.jsonPrimitive?.intOrNull
                val result = workspaceRepository.readKnowledge(workspaceId, path, start, end)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("path", result.path)
                    put("sourcePath", result.sourcePath)
                    put("startLine", result.startLine)
                    put("endLine", result.endLine)
                    put("text", result.text)
                    put("citation", result.citation)
                }.toString()))
            },
        ),
        Tool(
            name = "knowledge_ingest",
            description = "Persist a file from /upload into /workspace/vault/00_收件箱 without overwriting an existing entry, and build a local searchable representation when needed. Classification is intentionally deferred. This changes persistent vault data.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("upload_name", buildJsonObject { put("type", "string"); put("description", "File name inside /upload") })
                        put("mime_type", buildJsonObject { put("type", "string"); put("description", "Optional MIME type") })
                    },
                    required = listOf("upload_name"),
                )
            },
            needsApproval = { needsApproval("knowledge_ingest") },
            execute = { input ->
                val params = input.jsonObject
                val name = params["upload_name"]?.jsonPrimitive?.contentOrNull ?: error("upload_name is required")
                val mime = params["mime_type"]?.jsonPrimitive?.contentOrNull
                val result = knowledgeSpaceService.importUpload(workspaceId, name, mime)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("sourcePath", result.sourcePath)
                    result.normalizedPath?.let { put("normalizedPath", it) }
                    put("indexed", result.indexed)
                }.toString()))
            },
        ),
    )
}
