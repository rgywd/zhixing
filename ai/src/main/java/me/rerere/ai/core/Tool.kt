package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    /**
     * Must be deterministic and fail closed. Applied before tool input enters app state or storage;
     * the sanitized value is also used for approval and execution, so required fields must be preserved.
     */
    val sanitizeInputForStorage: (String) -> String = { it },
    /**
     * Pauses ordinary chat only when the tool needs an answer from the user before it can continue.
     * This is intentionally distinct from system permissions, privacy consent, and connection setup.
     */
    val requiresUserAnswer: Boolean = false,
    /**
     * Legacy per-tool approval policy. Kept so existing assistant/workspace/MCP configuration can
     * deserialize without a migration; ordinary chat no longer consults it before execution.
     */
    val needsApproval: (JsonElement) -> Boolean = { false },
    val executionMode: ToolExecutionMode = ToolExecutionMode.SERIAL,
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

@Serializable
enum class ToolExecutionMode {
    SERIAL,
    PARALLEL_READ_ONLY,
}

class ToolExecutionException(val code: String) : IllegalStateException(code)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
        val additionalProperties: Boolean? = null,
    ) : InputSchema()
}
