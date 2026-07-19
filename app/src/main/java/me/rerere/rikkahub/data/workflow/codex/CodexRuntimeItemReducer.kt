package me.rerere.rikkahub.data.workflow.codex

import kotlinx.serialization.json.JsonObject

/**
 * Reduces a live App Server Item event into the same [CodexItem] shape used by
 * a thread history snapshot. App Server deltas transported by the Agent are
 * cumulative, so a reconnect or repeated event remains idempotent.
 */
object CodexRuntimeItemReducer {
    fun apply(current: CodexItem?, event: RuntimeEventPayload): CodexItem {
        require(event.type in ITEM_EVENT_TYPES) { "not a Codex item event: ${event.type}" }
        val itemId = requireNotNull(event.itemId) { "item event missing itemId" }
        val itemType = event.itemType ?: current?.rawType ?: "opaque"
        val raw = JsonObject((current?.raw.orEmpty()) + event.payload)
        return CodexItem(
            itemId = itemId,
            type = itemType,
            rawType = itemType,
            role = event.role ?: current?.role ?: "unknown",
            text = event.text ?: current?.text,
            status = event.status
                ?: if (event.type == "item.completed") "completed" else current?.status ?: "inProgress",
            raw = raw,
        )
    }

    private val ITEM_EVENT_TYPES = setOf("item.started", "item.delta", "item.completed")
}
