package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

/** Keeps successful, explicitly read-only tool calls unique within one ordinary chat run. */
internal class ToolCallDeduplicator {
    private data class Key(
        val toolName: String,
        val canonicalArguments: String,
    )

    private data class InFlight(
        val originalToolCallId: String,
        val completedSuccessfully: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private val mutex = Mutex()
    private val successfulCalls = mutableMapOf<Key, String>()
    private val inFlightCalls = mutableMapOf<Key, InFlight>()

    suspend fun execute(
        toolCallId: String,
        toolName: String,
        arguments: JsonElement,
        enabled: Boolean,
        ignoredArgumentFields: Set<String> = emptySet(),
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        if (!enabled) return block()

        val key = Key(
            toolName = toolName,
            canonicalArguments = canonicalArguments(arguments, ignoredArgumentFields),
        )
        while (true) {
            var successfulOriginalId: String? = null
            var ownsExecution = false
            lateinit var inFlight: InFlight
            mutex.withLock {
                successfulOriginalId = successfulCalls[key]
                if (successfulOriginalId == null) {
                    inFlight = inFlightCalls[key] ?: InFlight(toolCallId).also { claimed ->
                        inFlightCalls[key] = claimed
                        ownsExecution = true
                    }
                }
            }

            successfulOriginalId?.let { return duplicateSkippedOutput(it) }
            if (!ownsExecution) {
                if (inFlight.completedSuccessfully.await()) {
                    return duplicateSkippedOutput(inFlight.originalToolCallId)
                }
                continue
            }

            try {
                val output = block()
                mutex.withLock {
                    inFlightCalls.remove(key)
                    successfulCalls[key] = toolCallId
                    inFlight.completedSuccessfully.complete(true)
                }
                return output
            } catch (throwable: Throwable) {
                mutex.withLock {
                    inFlightCalls.remove(key)
                    inFlight.completedSuccessfully.complete(false)
                }
                throw throwable
            }
        }
    }
}

private fun canonicalArguments(
    arguments: JsonElement,
    ignoredArgumentFields: Set<String>,
): String {
    val relevantArguments = if (arguments is JsonObject && ignoredArgumentFields.isNotEmpty()) {
        JsonObject(arguments.filterKeys { it !in ignoredArgumentFields })
    } else {
        arguments
    }
    return relevantArguments.toCanonicalJson()
}

private fun JsonElement.toCanonicalJson(): String = when (this) {
    is JsonObject -> entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${JsonPrimitive(key)}:${value.toCanonicalJson()}"
        }

    is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.toCanonicalJson() }
    else -> toString()
}

private fun duplicateSkippedOutput(originalToolCallId: String): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("status", "duplicate_skipped")
                put("original_tool_call_id", originalToolCallId)
                put("message", "Identical read-only tool call already succeeded in this run; reuse that result.")
            }.toString()
        )
    )
