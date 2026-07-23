package me.rerere.rikkahub.service

import kotlin.uuid.Uuid

internal data class ChatGenerationForegroundState(
    val activeGenerationCount: Int = 0,
    val activeConversationCount: Int = 0,
    val targetConversationId: Uuid? = null,
) {
    val isActive: Boolean
        get() = activeGenerationCount > 0
}

/**
 * Tracks individual generation runs instead of conversations so completion of a cancelled run
 * cannot remove foreground protection from its replacement.
 */
internal class ChatGenerationLeaseRegistry {
    private val generations = LinkedHashMap<Uuid, Uuid>()

    fun acquire(generationId: Uuid, conversationId: Uuid): ChatGenerationForegroundState {
        generations[generationId] = conversationId
        return snapshot()
    }

    fun release(generationId: Uuid): ChatGenerationForegroundState {
        generations.remove(generationId)
        return snapshot()
    }

    fun snapshot(): ChatGenerationForegroundState {
        val conversations = generations.values.distinct()
        return ChatGenerationForegroundState(
            activeGenerationCount = generations.size,
            activeConversationCount = conversations.size,
            targetConversationId = generations.values.lastOrNull(),
        )
    }
}
