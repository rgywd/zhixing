package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.data.workflow.codex.CodexModelOption
import me.rerere.rikkahub.data.workflow.codex.CodexPluginInterface
import me.rerere.rikkahub.data.workflow.codex.CodexPluginOption
import me.rerere.rikkahub.data.workflow.codex.CodexAppOption
import me.rerere.rikkahub.data.workflow.codex.CodexReasoningOption
import me.rerere.rikkahub.data.workflow.codex.CodexRuntimeSettingsState
import me.rerere.rikkahub.data.workflow.codex.CodexServiceTier

/** Converts the official App Server v2 catalog and runtime notifications into UI state. */
object AppServerRuntimeMapper {
    fun models(result: JsonElement?): List<CodexModelOption> {
        val data = (result as? JsonObject)?.array("data") ?: return emptyList()
        return data.mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            val id = raw.string("id") ?: return@mapNotNull null
            val efforts = raw.array("supportedReasoningEfforts").mapNotNull { effortElement ->
                val effort = effortElement as? JsonObject ?: return@mapNotNull null
                CodexReasoningOption(
                    reasoningEffort = effort.string("reasoningEffort") ?: return@mapNotNull null,
                    description = effort.string("description").orEmpty(),
                )
            }
            CodexModelOption(
                id = id,
                model = raw.string("model") ?: id,
                displayName = raw.string("displayName") ?: id,
                description = raw.string("description").orEmpty(),
                isDefault = raw.boolean("isDefault"),
                hidden = raw.boolean("hidden"),
                defaultReasoningEffort = raw.string("defaultReasoningEffort")
                    ?: efforts.firstOrNull()?.reasoningEffort
                    ?: "medium",
                supportedReasoningEfforts = efforts,
                inputModalities = raw.array("inputModalities").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .ifEmpty { listOf("text", "image") },
                serviceTiers = raw.array("serviceTiers").mapNotNull { tierElement ->
                    val tier = tierElement as? JsonObject ?: return@mapNotNull null
                    CodexServiceTier(
                        id = tier.string("id") ?: return@mapNotNull null,
                        name = tier.string("name") ?: tier.string("id").orEmpty(),
                        description = tier.string("description").orEmpty(),
                    )
                },
                defaultServiceTier = raw.string("defaultServiceTier"),
            )
        }
    }

    fun plugins(result: JsonElement?): List<CodexPluginOption> =
        ((result as? JsonObject)?.array("marketplaces").orEmpty())
            .flatMap { marketplace -> (marketplace as? JsonObject)?.array("plugins").orEmpty() }
            .mapNotNull { element ->
                val raw = element as? JsonObject ?: return@mapNotNull null
                val interfaceInfo = raw.objectValue("interface")
                CodexPluginOption(
                    id = raw.string("id") ?: return@mapNotNull null,
                    name = raw.string("name") ?: return@mapNotNull null,
                    installed = raw.boolean("installed"),
                    enabled = raw.boolean("enabled"),
                    availability = raw.string("availability")
                        ?: raw.objectValue("availability")?.string("type")
                        ?: "AVAILABLE",
                    interfaceInfo = interfaceInfo?.let {
                        CodexPluginInterface(it.string("displayName"), it.string("shortDescription"))
                    },
                )
            }
            .distinctBy(CodexPluginOption::id)

    fun apps(result: JsonElement?): List<CodexAppOption> =
        ((result as? JsonObject)?.array("data").orEmpty())
            .mapNotNull { element ->
                val raw = element as? JsonObject ?: return@mapNotNull null
                CodexAppOption(
                    id = raw.string("id") ?: return@mapNotNull null,
                    name = raw.string("name") ?: return@mapNotNull null,
                    description = raw.string("description"),
                    isAccessible = raw.boolean("isAccessible"),
                    isEnabled = raw.booleanOr("isEnabled", true),
                )
            }

    fun applyNotification(
        current: CodexRuntimeSettingsState,
        notification: AppServerNotification,
    ): CodexRuntimeSettingsState = when (notification.method) {
        "thread/settings/updated" -> {
            val settings = (notification.params as? JsonObject)?.objectValue("threadSettings") ?: return current
            current.copy(
                model = settings.string("model") ?: current.model,
                effort = settings.string("effort"),
                serviceTier = settings.string("serviceTier"),
                permissions = settings.objectValue("activePermissionProfile")?.string("id")
                    ?: sandboxPermission(settings.objectValue("sandboxPolicy"))
                    ?: current.permissions,
                updatedAt = System.currentTimeMillis(),
            )
        }
        "thread/tokenUsage/updated" -> {
            val usage = (notification.params as? JsonObject)?.objectValue("tokenUsage") ?: return current
            val total = usage.objectValue("total")
            current.copy(
                usedTokens = total?.long("totalTokens") ?: current.usedTokens,
                contextWindow = usage.long("modelContextWindow") ?: current.contextWindow,
                updatedAt = System.currentTimeMillis(),
            )
        }
        "model/rerouted" -> {
            val params = notification.params as? JsonObject ?: return current
            current.copy(
                model = params.string("toModel") ?: current.model,
                updatedAt = System.currentTimeMillis(),
            )
        }
        else -> current
    }

    private fun sandboxPermission(policy: JsonObject?): String? = when (policy?.string("type")) {
        "readOnly" -> "read-only"
        "workspaceWrite" -> "default"
        "dangerFullAccess" -> "full-access"
        else -> null
    }

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.booleanOr(key: String, default: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
