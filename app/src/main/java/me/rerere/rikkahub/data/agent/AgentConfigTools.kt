package me.rerere.rikkahub.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantUserPromptSource
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import kotlin.uuid.Uuid

internal fun agentSchema(fields: Map<String, String>, required: List<String>): InputSchema = InputSchema.Obj(
    properties = buildJsonObject { fields.forEach { (name, type) -> put(name, buildJsonObject {
        put("type", type)
        if (type == "array") put("items", buildJsonObject { put("type", "string") })
    }) } }, required = required, additionalProperties = false,
)

internal suspend fun agentResult(block: suspend () -> JsonObject): List<UIMessagePart> = try {
    listOf(UIMessagePart.Text(block().toString()))
} catch (e: CancellationException) { throw e
} catch (e: IllegalArgumentException) {
    val code = e.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "INVALID_INPUT"
    listOf(UIMessagePart.Text(buildJsonObject {
        put("success", false); put("error", code)
        if (code == "READ_BEFORE_WRITE" || code == "REVISION_CONFLICT") {
            put("hint", "Read the target again, preserve unrelated changes, then write using its latest revision.")
        }
    }.toString()))
} catch (_: Exception) {
    listOf(UIMessagePart.Text("{\"success\":false,\"error\":\"EXECUTION_FAILED\"}"))
}

internal fun JsonObject.text(name: String): String = (get(name) as? JsonPrimitive)?.takeIf { it.isString }
    ?.content?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("INVALID_INPUT")
internal fun JsonObject.strings(name: String): Set<String> = (get(name) as? JsonArray)?.map {
    (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: throw IllegalArgumentException("INVALID_INPUT")
}?.toSet() ?: throw IllegalArgumentException("INVALID_INPUT")

internal fun Assistant.configJson() = buildJsonObject {
    put("id", id.toString()); put("revision", configRevision); put("name", name); put("description", description)
    put("enabled", isEnabled); put("model_id", chatModelId?.toString()); put("prompt", systemPrompt)
    put("workspace_id", workspaceId?.toString()); put("temperature", temperature?.let(::JsonPrimitive) ?: JsonNull)
    put("max_tokens", maxTokens?.let(::JsonPrimitive) ?: JsonNull); put("reasoning_level", reasoningLevel.name)
    put("use_global_memory", useGlobalMemory)
    put("prompt_source", userPromptSource.name); put("managed_by", managedBy?.toString())
    put("capabilities", JsonArray((capabilities ?: AgentCapabilities.all).sorted().map(::JsonPrimitive)))
    put("skills", JsonArray(enabledSkills.sorted().map(::JsonPrimitive)))
    put("mcp_servers", JsonArray(mcpServers.map { JsonPrimitive(it.toString()) }))
}

fun createAgentConfigTools(actor: Assistant, store: SettingsStore, skills: SkillManager, workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository): List<Tool> = agentConfigTools(
    actor, { store.settingsFlowRaw.first() }, { store.update(it) }, { skills.listSkills().map { it.name } },
    { workspaceRepository.listFlow().first().associate { it.id to it.name } },
)

internal fun agentConfigTools(
    actor: Assistant,
    readSettings: suspend () -> Settings,
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    skillNames: () -> List<String>,
    workspaces: suspend () -> Map<String, String> = { emptyMap() },
): List<Tool> = listOf(
    Tool(
        name = "agent_catalog",
        description = "Discover this agent, its managed agents, configured model IDs, available capabilities, skills and connected MCP IDs. Secrets are never returned. Use IDs from this result, not guesses.",
        parameters = { agentSchema(emptyMap(), emptyList()) },
        execute = { agentResult {
            val settings = readSettings()
            val current = settings.assistants.find { it.id == actor.id } ?: error("Agent missing")
            val spaces = workspaces()
            buildJsonObject {
                put("workspaces", JsonArray(spaces.map { (id, name) -> buildJsonObject { put("id", id); put("name", name) } }))
                put("success", true); put("self", current.configJson())
                put("agents", JsonArray(settings.assistants.filter { AgentCapabilities.canManage(current, it) }.map { it.configJson() }))
                put("models", buildJsonArray { settings.providers.forEach { provider -> provider.models.forEach { model ->
                    add(buildJsonObject { put("id", model.id.toString()); put("name", model.displayName)
                        put("provider", provider.name); put("abilities", JsonArray(model.abilities.map { JsonPrimitive(it.name) })) })
                } } })
                put("skills", JsonArray(skillNames().map(::JsonPrimitive)))
                put("capabilities", JsonArray(AgentCapabilities.all.sorted().map(::JsonPrimitive)))
                put("mcp_servers", buildJsonArray { settings.mcpServers.filter { it.commonOptions.enable }.forEach {
                    add(buildJsonObject { put("id", it.id.toString()); put("name", it.commonOptions.name) })
                } })
            }
        } },
    ),
    Tool(
        name = "agent_config",
        description = "Create or patch self/a managed child agent. Read agent_catalog first. create requires name; patch/restore require agent_id and if_revision; restore returns to the previous configuration. Omitted fields stay unchanged; capabilities and skills are replacement arrays. Capabilities select actual tools. New children are isolated, have no management rights, and can use only the selected capabilities. prompt changes apply to new conversations; other changes to the next run. Disable with enabled=false; never deletes conversation data. Model and MCP IDs must exist. Do not modify configuration without a user-requested purpose.",
        parameters = { agentSchema(mapOf("action" to "string", "agent_id" to "string", "if_revision" to "integer",
            "name" to "string", "description" to "string", "model_id" to "string", "prompt" to "string",
            "workspace_id" to "string", "temperature" to "number", "max_tokens" to "integer", "reasoning_level" to "string", "use_global_memory" to "boolean",
            "enabled" to "boolean", "capabilities" to "array", "skills" to "array", "mcp_servers" to "array"), listOf("action")) },
        execute = { input -> agentResult {
            val p = input.jsonObject
            require(p.keys.all { it in setOf("action", "agent_id", "if_revision", "name", "description", "model_id", "prompt", "enabled", "capabilities", "skills", "mcp_servers", "workspace_id", "temperature", "max_tokens", "reasoning_level", "use_global_memory") }) { "INVALID_INPUT" }
            val action = p.text("action"); require(action in setOf("create", "patch", "restore")) { "INVALID_INPUT" }
            val id = if (action == "create" && "agent_id" !in p) Uuid.random() else Uuid.parse(p.text("agent_id"))
            val availableWorkspaces = workspaces()
            var receipt: Assistant? = null
            updateSettings { settings ->
                val current = settings.assistants.find { it.id == actor.id } ?: throw IllegalArgumentException("AGENT_NOT_FOUND")
                require(current.isEnabled && AgentCapabilities.permits(current, "agent_config")) { "CAPABILITY_DENIED" }
                val old = if (action == "create") {
                    require(current.managedBy == null) { "CAPABILITY_DENIED" }
                    require(settings.assistants.none { it.id == id }) { "FILE_EXISTS" }
                    require(settings.assistants.count { it.managedBy == current.id } < 32) { "AGENT_LIMIT" }
                    Assistant(id = id, name = p.text("name"), managedBy = current.id, capabilities = emptySet(), streamOutput = current.streamOutput,
                        chatModelId = current.chatModelId ?: settings.chatModelId,
                        localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.AskUser))
                } else settings.assistants.find { it.id == id } ?: throw IllegalArgumentException("AGENT_NOT_FOUND")
                require(AgentCapabilities.canManage(current, old)) { "CAPABILITY_DENIED" }
                if (action != "create") require(p["if_revision"]?.jsonPrimitive?.longOrNull == old.configRevision) { "REVISION_CONFLICT" }
                val caps = if ("capabilities" in p) p.strings("capabilities") else old.capabilities
                require(caps == null || AgentCapabilities.all.containsAll(caps)) { "UNKNOWN_CAPABILITY" }
                require(old.managedBy == null || caps?.contains("agents") != true) { "CAPABILITY_DENIED" }
                val modelId = if ("model_id" in p) p["model_id"]?.takeUnless { it == JsonNull }?.let { Uuid.parse(p.text("model_id")) } else old.chatModelId
                require(action != "create" && "model_id" !in p || modelId == null || settings.findModelById(modelId) != null) { "MODEL_NOT_FOUND" }
                val selectedSkills = if ("skills" in p) p.strings("skills") else old.enabledSkills
                require(action != "create" && "skills" !in p || skillNames().containsAll(selectedSkills)) { "SKILL_NOT_FOUND" }
                val servers = if ("mcp_servers" in p) p.strings("mcp_servers").map(Uuid::parse).toSet() else old.mcpServers
                require(action != "create" && "mcp_servers" !in p || servers.all { id -> settings.mcpServers.any { it.id == id && it.commonOptions.enable } }) { "MCP_NOT_AVAILABLE" }
                val workspaceId = if ("workspace_id" in p) p["workspace_id"]?.takeUnless { it == JsonNull }?.let { Uuid.parse(p.text("workspace_id")) } else
                    if (old.managedBy != null && caps?.any { it in setOf("workspace", "knowledge") } == true) current.workspaceId else old.workspaceId
                require(action != "create" && "workspace_id" !in p || workspaceId == null || workspaceId.toString() in availableWorkspaces) { "WORKSPACE_NOT_FOUND" }
                val temperature = if ("temperature" in p) p["temperature"]?.takeUnless { it == JsonNull }?.let { it.jsonPrimitive.floatOrNull ?: throw IllegalArgumentException("INVALID_INPUT") } else old.temperature
                require(temperature == null || temperature.isFinite() && temperature in 0f..2f) { "INVALID_INPUT" }
                val maxTokens = if ("max_tokens" in p) p["max_tokens"]?.takeUnless { it == JsonNull }?.let { it.jsonPrimitive.intOrNull ?: throw IllegalArgumentException("INVALID_INPUT") } else old.maxTokens
                require(maxTokens == null || maxTokens in 1..262144) { "INVALID_INPUT" }
                val restored = if (action == "restore") me.rerere.rikkahub.utils.JsonInstant.decodeFromString<Assistant>(
                    old.previousConfiguration ?: throw IllegalArgumentException("NO_PREVIOUS_CONFIGURATION")) else null
                val updated = restored?.copy(id = old.id, managedBy = old.managedBy, configRevision = old.configRevision,
                    previousConfiguration = old.previousConfiguration) ?: old.copy(
                    name = if ("name" in p) p.text("name") else old.name,
                    description = if ("description" in p) p["description"]!!.jsonPrimitive.content else old.description,
                    systemPrompt = if ("prompt" in p) p["prompt"]!!.jsonPrimitive.content else old.systemPrompt,
                    userPromptSource = if ("prompt" in p) AssistantUserPromptSource.APP else old.userPromptSource,
                    workspaceId = workspaceId, temperature = temperature, maxTokens = maxTokens,
                    reasoningLevel = if ("reasoning_level" in p) me.rerere.ai.core.ReasoningLevel.valueOf(p.text("reasoning_level")) else old.reasoningLevel,
                    useGlobalMemory = if ("use_global_memory" in p) p["use_global_memory"]!!.jsonPrimitive.boolean else old.useGlobalMemory,
                    chatModelId = modelId, capabilities = caps, enabledSkills = selectedSkills, mcpServers = servers,
                    isEnabled = if ("enabled" in p) p["enabled"]!!.jsonPrimitive.boolean else old.isEnabled,
                    enableWebSearch = caps?.contains("search") ?: old.enableWebSearch,
                    enableMemory = caps?.contains("memory") ?: old.enableMemory,
                    enableRecentChatsReference = caps?.contains("history") ?: old.enableRecentChatsReference,
                )
                require(updated.name.length <= 100 && updated.description.length <= 2000 && updated.systemPrompt.length <= 32000) { "INPUT_TOO_LARGE" }
                receipt = updated.copy(configRevision = if (action == "create" || old == updated) old.configRevision else old.configRevision + 1)
                settings.copy(assistants = if (action == "create") settings.assistants + updated else settings.assistants.map { if (it.id == id) updated else it })
            }
            buildJsonObject { put("success", true); put("agent", checkNotNull(receipt).configJson()); put("applies_to", "next_run; prompt:new_conversations") }
        } },
    ),
)
