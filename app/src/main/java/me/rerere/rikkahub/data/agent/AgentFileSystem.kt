package me.rerere.rikkahub.data.agent

import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/** A mounted file view over the same transactions used by Settings UI and backups. */
internal class AgentFileSystem(
    private val actor: Assistant,
    private val readSettings: suspend () -> Settings,
    private val configTools: List<Tool>,
    private val skillTool: Tool,
    private val applyPrompt: suspend () -> Unit,
) {
    private val readVersions = mutableMapOf<String, String>()
    private val metadata = setOf("id", "revision", "managed_by", "prompt_source")
    private val pretty = Json { prettyPrint = true }

    private suspend fun call(tool: Tool, args: JsonObject): JsonObject {
        val result = Json.parseToJsonElement(tool.execute(args).filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
        require(result["success"]?.jsonPrimitive?.boolean != false) { result["error"]!!.jsonPrimitive.content }
        return result
    }

    private fun normalize(path: String): String {
        require(path.startsWith('/') && path.split('/').none { it == "." || it == ".." } && '\\' !in path) { "INVALID_PATH" }
        return path.trimEnd('/').replace("/agents/self/", "/agents/${actor.id}/")
    }

    private suspend fun permitted(): Settings = readSettings().also { settings ->
        val current = settings.assistants.find { it.id == actor.id }
        require(current != null && current.managedBy == null && AgentCapabilities.permits(current, "agent_config")) { "CAPABILITY_DENIED" }
    }

    private fun Settings.target(path: String): Assistant? {
        val id = Uuid.parse(path.split('/')[2])
        val target = assistants.find { it.id == id }
        require(target == null || AgentCapabilities.canManage(assistants.first { it.id == actor.id }, target)) { "CAPABILITY_DENIED" }
        return target
    }

    suspend fun read(rawPath: String): JsonObject {
        val path = normalize(rawPath)
        val settings = permitted()
        val text: String?
        val revision: String
        when {
            path == "/agents" || path == "/agents/self" -> {
                text = if (path == "/agents/self") "config.json\nAGENT.md\nprevious.json" else
                    "self/\nresources.json\n" + settings.assistants.filter { AgentCapabilities.canManage(settings.assistants.first { a -> a.id == actor.id }, it) }
                        .joinToString("\n") { "${it.id}/ (${it.name})" }
                revision = settings.revision.toString()
            }
            path == "/agents/resources.json" -> {
                val catalog = call(configTools.first(), buildJsonObject {})
                text = pretty.encodeToString(JsonObject(catalog.filterKeys { it !in setOf("self", "agents", "success") }))
                revision = settings.revision.toString()
            }
            path == "/skills" -> {
                val catalog = call(configTools.first(), buildJsonObject {})
                text = catalog["skills"]!!.jsonArray.joinToString("\n") { "${it.jsonPrimitive.content}/SKILL.md" }
                revision = skillRevision(text)
            }
            path.startsWith("/skills/") -> {
                val result = call(skillTool, buildJsonObject { put("action", "read"); put("name", skillName(path)) })
                text = result["content"]?.jsonPrimitive?.contentOrNull
                revision = result["revision"]!!.jsonPrimitive.content
            }
            path.startsWith("/agents/") -> {
                val parts = path.split('/')
                require(parts.size in 3..4) { "INVALID_PATH" }
                val target = settings.target(path)
                text = when (parts.getOrNull(3)) {
                    null -> target?.let { "config.json\nAGENT.md\nprevious.json" }
                    "AGENT.md" -> target?.systemPrompt
                    "config.json" -> target?.let { pretty.encodeToString(JsonObject(it.configJson().filterKeys { key -> key != "prompt" })) }
                    "previous.json" -> target?.previousConfiguration?.let { previous ->
                        pretty.encodeToString(me.rerere.rikkahub.utils.JsonInstant.decodeFromString<Assistant>(previous).configJson())
                    }
                    else -> throw IllegalArgumentException("FILE_NOT_FOUND")
                }
                revision = target?.configRevision?.toString() ?: ""
            }
            else -> throw IllegalArgumentException("FILE_NOT_FOUND")
        }
        readVersions[path] = revision
        return buildJsonObject { put("path", path); put("text", text); put("exists", text != null); put("revision", revision) }
    }

    private fun skillName(path: String): String {
        val parts = path.split('/')
        require(parts.size == 4 && parts[3] == "SKILL.md") { "INVALID_PATH" }
        return parts[2]
    }

    suspend fun write(rawPath: String, text: String, overwrite: Boolean, expected: String? = null): JsonObject {
        val path = normalize(rawPath)
        val settings = permitted()
        val revision = expected ?: readVersions[path]
        val result = when {
            path.startsWith("/skills/") -> call(skillTool, buildJsonObject {
                put("action", "write"); put("name", skillName(path)); put("content", text)
                put("if_revision", if (!overwrite) "" else revision ?: throw IllegalArgumentException("READ_BEFORE_WRITE"))
            })
            path.startsWith("/agents/") -> {
                val parts = path.split('/')
                require(parts.size == 4 && parts[3] in setOf("config.json", "AGENT.md")) { "READ_ONLY_PATH" }
                val target = settings.target(path)
                require(overwrite || target == null) { "FILE_EXISTS" }
                require(target != null || parts[3] == "config.json") { "CREATE_CONFIG_FIRST" }
                require(target == null || revision == target.configRevision.toString()) { "REVISION_CONFLICT" }
                val fields = if (parts[3] == "AGENT.md") buildJsonObject { put("prompt", text) } else {
                    val proposed = runCatching { Json.parseToJsonElement(text).jsonObject }
                        .getOrElse { throw IllegalArgumentException("INVALID_CONFIG_JSON") }
                    require(proposed.keys.none { it in setOf("action", "agent_id", "if_revision", "prompt") }) { "INVALID_INPUT" }
                    if (target != null) require(metadata.all { it !in proposed || proposed[it] == target.configJson()[it] }) { "READ_ONLY_FIELD" }
                    else require(proposed.keys.none { it in metadata }) { "READ_ONLY_FIELD" }
                    JsonObject(proposed.filter { (key, value) -> key !in metadata && (target == null || target.configJson()[key] != value) })
                }
                call(configTools.last(), buildJsonObject {
                    fields.forEach { (key, value) -> put(key, value) }
                    put("action", if (target == null) "create" else "patch"); put("agent_id", parts[2])
                    if (target != null) put("if_revision", target.configRevision)
                })
            }
            else -> throw IllegalArgumentException("READ_ONLY_PATH")
        }
        readVersions.remove(path)
        // The current run stays frozen. The next user turn reads the updated self prompt.
        if (path == "/agents/${actor.id}/AGENT.md") applyPrompt()
        return buildJsonObject { put("success", true); put("path", path); put("applies_to", if (path.endsWith("/AGENT.md") && path != "/agents/${actor.id}/AGENT.md") "new_conversations" else if (path.startsWith("/skills/")) "next_read" else "next_run"); put("result", result) }
    }

    fun mount(workspaceTools: List<Tool>): List<Tool> {
        val names = listOf("workspace_read_file", "workspace_write_file", "workspace_edit_file")
        return workspaceTools.filter { it.name !in names } + names.map { name ->
            val fallback = workspaceTools.find { it.name == name }
            val fields = when (name) {
                names[0] -> mapOf("path" to "string")
                names[1] -> mapOf("path" to "string", "text" to "string", "overwrite" to "boolean", "if_revision" to "string")
                else -> mapOf("path" to "string", "old_text" to "string", "new_text" to "string", "replace_all" to "boolean")
            }
            Tool(name = name, description = (fallback?.description ?: "Read/write/edit UTF-8 files.") +
                " Managed mounts: /agents (read directories to list), /agents/self/AGENT.md (your prompt), /agents/self/config.json, /agents/resources.json (available model/skill/MCP IDs), /skills/<name>/SKILL.md. Read before overwriting; edits use a fresh read and exact matching. Create children by writing /agents/<new UUID>/config.json with name and selected capabilities, then AGENT.md. config.json fields: name, description, enabled, model_id, workspace_id, temperature, max_tokens, reasoning_level, use_global_memory, capabilities, skills, mcp_servers; id/revision/managed_by/prompt_source are read-only. No shell needed. Self prompt edits apply next turn; child prompt edits apply new conversations. Only make changes for a user-requested purpose.",
                parameters = { agentSchema(fields, when (name) { names[0] -> listOf("path"); names[1] -> listOf("path", "text"); else -> listOf("path", "old_text", "new_text") }) },
                execute = { input ->
                    val p = input.jsonObject
                    val path = p.text("path")
                    if (path == "/agents" || path.startsWith("/agents/") || path == "/skills" || path.startsWith("/skills/")) agentResult {
                        when (name) {
                            names[0] -> read(path)
                            names[1] -> write(path, p["text"]!!.jsonPrimitive.content, p["overwrite"]?.jsonPrimitive?.boolean ?: true, p["if_revision"]?.jsonPrimitive?.content)
                            else -> {
                                val original = read(path)["text"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("FILE_NOT_FOUND")
                                val old = p.text("old_text")
                                require(original.contains(old)) { "TEXT_NOT_FOUND" }
                                val all = p["replace_all"]?.jsonPrimitive?.boolean ?: false
                                require(all || original.indexOf(old) == original.lastIndexOf(old)) { "AMBIGUOUS_MATCH" }
                                write(path, if (all) original.replace(old, p["new_text"]!!.jsonPrimitive.content) else original.replaceFirst(old, p["new_text"]!!.jsonPrimitive.content), true)
                            }
                        }
                    } else {
                        val current = readSettings().assistants.find { it.id == actor.id }
                        if (fallback != null && current != null && (current.capabilities == null || "workspace" in current.capabilities)) fallback.execute(input)
                        else listOf(UIMessagePart.Text("{\"success\":false,\"error\":\"WORKSPACE_NOT_AVAILABLE\"}"))
                    }
                })
        }
    }
}
