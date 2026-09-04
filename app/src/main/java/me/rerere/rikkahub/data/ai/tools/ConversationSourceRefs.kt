package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolExecutionException
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryDocumentSource
import me.rerere.rikkahub.data.model.MemoryDocumentSourceType
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.time.Instant
import kotlin.uuid.Uuid

private const val SOURCE_REF_VERSION = "v1"

internal data class ConversationSourceRef(
    val conversationId: Uuid,
    val nodeId: Uuid,
    val messageId: Uuid,
) {
    fun encode(): String = listOf(
        SOURCE_REF_VERSION,
        conversationId,
        nodeId,
        messageId,
    ).joinToString(".")

    companion object {
        fun decode(value: String): ConversationSourceRef {
            val parts = value.split('.')
            if (parts.size != 4 || parts.first() != SOURCE_REF_VERSION) {
                throw ToolExecutionException("MEMORY_SOURCE_REF_INVALID")
            }
            return runCatching {
                ConversationSourceRef(
                    conversationId = Uuid.parse(parts[1]),
                    nodeId = Uuid.parse(parts[2]),
                    messageId = Uuid.parse(parts[3]),
                )
            }.getOrElse { throw ToolExecutionException("MEMORY_SOURCE_REF_INVALID") }
        }
    }
}

internal data class ResolvedConversationUserSource(
    val sourceRef: ConversationSourceRef,
    val title: String,
    val updateAt: Instant,
    val textParts: List<String>,
)

internal fun resolveSelectedUserSource(
    conversation: Conversation,
    sourceRef: ConversationSourceRef,
    assistantId: Uuid,
    excludedConversationId: Uuid?,
): ResolvedConversationUserSource {
    if (
        conversation.id != sourceRef.conversationId ||
        conversation.assistantId != assistantId ||
        conversation.id == excludedConversationId
    ) {
        throw ToolExecutionException("MEMORY_SOURCE_NOT_FOUND")
    }
    val node = conversation.messageNodes.firstOrNull { it.id == sourceRef.nodeId }
        ?: throw ToolExecutionException("MEMORY_SOURCE_NOT_FOUND")
    val message = node.messages.getOrNull(node.selectIndex)
        ?: throw ToolExecutionException("MEMORY_SOURCE_NOT_FOUND")
    if (message.id != sourceRef.messageId || message.role != MessageRole.USER) {
        throw ToolExecutionException("MEMORY_SOURCE_ROLE_INVALID")
    }
    val textParts = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .map(UIMessagePart.Text::text)
        .filter(String::isNotBlank)
    if (textParts.isEmpty()) throw ToolExecutionException("MEMORY_SOURCE_NOT_FOUND")
    return ResolvedConversationUserSource(
        sourceRef = sourceRef,
        title = conversation.title,
        updateAt = conversation.updateAt,
        textParts = textParts,
    )
}

internal suspend fun resolveHistoricalMemorySource(
    conversationRepository: ConversationRepository,
    assistantId: Uuid,
    currentConversationId: Uuid,
    encodedSourceRef: String,
    quote: String,
): MemoryDocumentSource {
    val sourceRef = ConversationSourceRef.decode(encodedSourceRef)
    val conversation = conversationRepository.getConversationById(sourceRef.conversationId)
        ?: throw ToolExecutionException("MEMORY_SOURCE_NOT_FOUND")
    val resolved = resolveSelectedUserSource(
        conversation = conversation,
        sourceRef = sourceRef,
        assistantId = assistantId,
        excludedConversationId = currentConversationId,
    )
    if (resolved.textParts.none { it.contains(quote) }) {
        throw ToolExecutionException("MEMORY_SOURCE_QUOTE_MISMATCH")
    }
    return MemoryDocumentSource(
        type = MemoryDocumentSourceType.CHAT,
        conversationId = sourceRef.conversationId.toString(),
        messageId = sourceRef.messageId.toString(),
        quote = quote,
        observedAt = System.currentTimeMillis(),
    )
}
