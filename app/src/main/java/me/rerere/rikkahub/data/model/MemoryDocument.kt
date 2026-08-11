package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MemoryDocumentSource(
    val type: MemoryDocumentSourceType,
    val conversationId: String = "",
    val messageId: String = "",
    val quote: String = "",
    val observedAt: Long = 0,
)
@Serializable
enum class MemoryDocumentSourceType {
    CHAT,
    USER_EDIT,
    MIGRATION,
}

@Serializable
enum class MemoryDocumentState {
    ACTIVE,
    DELETED,
}

@Serializable
data class MemoryDocument(
    val scopeId: String,
    val path: String,
    val name: String,
    val description: String,
    val aliases: List<String> = emptyList(),
    val content: String = "",
    val sources: List<MemoryDocumentSource> = emptyList(),
    val version: Long = 1,
    val state: MemoryDocumentState = MemoryDocumentState.ACTIVE,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
