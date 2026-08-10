package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import java.util.Locale
import kotlin.uuid.Uuid

private const val MONTHLY_SPENDING_SUMMARY_TOOL_NAME = "monthly_spending_summary"
private const val SAVE_ACTION = "save"
private const val LOCAL_FILE_URI_PREFIX = "file://"
private const val SOURCE_MESSAGE_IDS_METADATA_KEY =
    "zhixing_monthly_spending_source_message_ids_v1"

internal data class MonthlySpendingToolCallRef(
    val assistantMessageId: Uuid,
    val toolCallId: String,
)

internal data class MonthlySpendingAttachmentRedaction(
    val sourceMessageIds: Set<Uuid>,
    val attachmentUris: Set<String>,
)

internal data class MonthlySpendingAttachmentCleanupCandidate(
    val toolCalls: Set<MonthlySpendingToolCallRef>,
    val sourceMessageIds: Set<Uuid>,
    val attachmentUris: Set<String>,
) {
    val redaction: MonthlySpendingAttachmentRedaction
        get() = MonthlySpendingAttachmentRedaction(
            sourceMessageIds = sourceMessageIds,
            attachmentUris = attachmentUris,
        )
}

/**
 * Binds newly generated monthly-save tools to the exact user message that supplied their files.
 *
 * Only message IDs are stored in tool metadata. File names, paths, OCR text, and tool input are not
 * duplicated. Later save calls in the same selected tool chain reuse the same source-message set,
 * so a multi-currency import remains one cleanup batch across multiple approval resumptions.
 */
internal fun Conversation.bindMonthlySpendingSaveSourceMessages(): Conversation {
    val selectedMessages = currentMessages
    val bindings = mutableMapOf<Uuid, Set<Uuid>>()

    selectedMessages.forEachIndexed { index, message ->
        if (message.role != MessageRole.ASSISTANT) return@forEachIndexed
        val hasUnboundSave = message.parts
            .filterIsInstance<UIMessagePart.Tool>()
            .any { tool ->
                tool.output.isEmpty() &&
                    tool.isMonthlySpendingSave() &&
                    tool.sourceMessageIdsOrNull() == null
            }
        if (!hasUnboundSave) return@forEachIndexed

        val priorMessages = selectedMessages.take(index)
        val lastUserIndex = priorMessages.indexOfLast { it.role == MessageRole.USER }
        val existingSourceIds = priorMessages
            .drop(lastUserIndex + 1)
            .asReversed()
            .asSequence()
            .filter { it.role == MessageRole.ASSISTANT }
            .flatMap { it.parts.asSequence().filterIsInstance<UIMessagePart.Tool>() }
            .filter { it.isMonthlySpendingSave() }
            .mapNotNull { it.sourceMessageIdsOrNull() }
            .firstOrNull()

        val sourceIds = existingSourceIds ?: priorMessages
            .getOrNull(lastUserIndex)
            ?.takeIf { candidate ->
                candidate.localImageAndDocumentUris().isNotEmpty()
            }
            ?.let { setOf(it.id) }
            ?: return@forEachIndexed

        bindings[message.id] = sourceIds
    }

    if (bindings.isEmpty()) return this
    return copy(
        messageNodes = messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { message ->
                    val sourceIds = bindings[message.id] ?: return@map message
                    message.copy(
                        parts = message.parts.map { part ->
                            if (
                                part is UIMessagePart.Tool &&
                                part.output.isEmpty() &&
                                part.isMonthlySpendingSave() &&
                                part.sourceMessageIdsOrNull() == null
                            ) {
                                part.withSourceMessageIds(sourceIds)
                            } else {
                                part
                            }
                        },
                    )
                },
            )
        },
    )
}

/**
 * Finds cleanup batches that are safe to finalize.
 *
 * A batch is ready only when every monthly-save tool bound to the same source messages is an
 * strict business success and the selected branch has no unexecuted tool of any kind.
 * Tool identity includes the containing assistant-message ID, so provider-local call IDs cannot
 * collide with an older response or a sibling branch.
 */
internal fun Conversation.findReadyMonthlySpendingAttachmentCleanupCandidates(
    eligibleToolCalls: Set<MonthlySpendingToolCallRef>? = null,
): List<MonthlySpendingAttachmentCleanupCandidate> {
    if (currentMessages.any { message -> message.tools().any { !it.isExecuted } }) {
        return emptyList()
    }

    val markedSaveTools = messageNodes
        .asSequence()
        .flatMap { it.messages.asSequence() }
        .flatMap { message ->
            message.tools()
                .asSequence()
                .filter { it.isMonthlySpendingSave() }
                .mapNotNull { tool ->
                    tool.sourceMessageIdsOrNull()?.let { sourceIds ->
                        MarkedSaveTool(
                            ref = MonthlySpendingToolCallRef(
                                assistantMessageId = message.id,
                                toolCallId = tool.toolCallId,
                            ),
                            sourceMessageIds = sourceIds,
                            tool = tool,
                        )
                    }
                }
        }
        .toList()

    return markedSaveTools
        .groupBy(MarkedSaveTool::sourceMessageIds)
        .mapNotNull { (sourceMessageIds, tools) ->
            if (
                eligibleToolCalls != null &&
                tools.none { it.ref in eligibleToolCalls }
            ) {
                return@mapNotNull null
            }
            if (
                sourceMessageIds.isEmpty() ||
                tools.isEmpty() ||
                tools.any { marked ->
                    !marked.tool.hasSuccessfulMonthlySpendingSaveOutput()
                }
            ) {
                return@mapNotNull null
            }

            val attachmentUris = sourceMessages(sourceMessageIds)
                .flatMapTo(mutableSetOf()) { it.localImageAndDocumentUris() }
            if (attachmentUris.isEmpty()) return@mapNotNull null

            MonthlySpendingAttachmentCleanupCandidate(
                toolCalls = tools.mapTo(mutableSetOf(), MarkedSaveTool::ref),
                sourceMessageIds = sourceMessageIds,
                attachmentUris = attachmentUris,
            )
        }
}

internal fun Conversation.successfulMonthlySpendingSaveToolCalls():
    Set<MonthlySpendingToolCallRef> = messageNodes
    .asSequence()
    .flatMap { it.messages.asSequence() }
    .flatMap { message ->
        message.tools()
            .asSequence()
            .filter { tool ->
                tool.isMonthlySpendingSave() &&
                    tool.sourceMessageIdsOrNull() != null &&
                    tool.hasSuccessfulMonthlySpendingSaveOutput()
            }
            .map { tool ->
                MonthlySpendingToolCallRef(
                    assistantMessageId = message.id,
                    toolCallId = tool.toolCallId,
                )
            }
    }
    .toSet()

internal fun Conversation.isReadyForMonthlySpendingAttachmentCleanup(
    candidate: MonthlySpendingAttachmentCleanupCandidate,
): Boolean = findReadyMonthlySpendingAttachmentCleanupCandidates().any { current ->
    current.sourceMessageIds == candidate.sourceMessageIds &&
        current.toolCalls == candidate.toolCalls &&
        current.attachmentUris.containsAll(candidate.attachmentUris)
}

/**
 * Removes only the captured image/document URI references from the bound source messages.
 *
 * Other branches, messages, media types, and nested tool output remain intact. Those remaining
 * references are considered separately before the underlying physical file is deleted.
 */
internal fun Conversation.redactMonthlySpendingAttachments(
    redaction: MonthlySpendingAttachmentRedaction,
    placeholder: String,
): Conversation {
    require(placeholder.isNotBlank()) { "placeholder must not be blank" }
    if (redaction.sourceMessageIds.isEmpty() || redaction.attachmentUris.isEmpty()) return this

    return copy(
        messageNodes = messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { message ->
                    if (message.id in redaction.sourceMessageIds) {
                        message.redactMonthlySpendingAttachments(
                            attachmentUris = redaction.attachmentUris,
                            placeholder = placeholder,
                        )
                    } else {
                        message
                    }
                },
            )
        },
    )
}

private fun UIMessage.redactMonthlySpendingAttachments(
    attachmentUris: Set<String>,
    placeholder: String,
): UIMessage {
    var removedAttachment = false
    val redactedParts = parts.filterNot { part ->
        val shouldRemove = when (part) {
            is UIMessagePart.Image -> part.url in attachmentUris
            is UIMessagePart.Document -> part.url in attachmentUris
            else -> false
        }
        removedAttachment = removedAttachment || shouldRemove
        shouldRemove
    }
    if (!removedAttachment) return this

    return copy(
        parts = redactedParts.ifEmpty {
            listOf(UIMessagePart.Text(placeholder))
        },
    )
}

internal fun Conversation.containsMonthlySpendingAttachmentUris(
    redaction: MonthlySpendingAttachmentRedaction,
): Boolean {
    if (redaction.sourceMessageIds.isEmpty() || redaction.attachmentUris.isEmpty()) return false
    return sourceMessages(redaction.sourceMessageIds).any { message ->
        message.parts.any { part ->
            when (part) {
                is UIMessagePart.Image -> part.url in redaction.attachmentUris
                is UIMessagePart.Document -> part.url in redaction.attachmentUris
                else -> false
            }
        }
    }
}

private data class MarkedSaveTool(
    val ref: MonthlySpendingToolCallRef,
    val sourceMessageIds: Set<Uuid>,
    val tool: UIMessagePart.Tool,
)

private fun Conversation.sourceMessages(sourceMessageIds: Set<Uuid>): List<UIMessage> =
    messageNodes
        .flatMap { it.messages }
        .filter { it.id in sourceMessageIds && it.role == MessageRole.USER }

private fun UIMessage.tools(): List<UIMessagePart.Tool> =
    parts.filterIsInstance<UIMessagePart.Tool>()

private fun UIMessage.localImageAndDocumentUris(): Set<String> = parts
    .mapNotNull { part ->
        when (part) {
            is UIMessagePart.Image -> part.url.takeIf { it.isLocalFileUri() }
            is UIMessagePart.Document -> part.url.takeIf { it.isLocalFileUri() }
            else -> null
        }
    }
    .toSet()

private fun UIMessagePart.Tool.isMonthlySpendingSave(): Boolean =
    toolCallId.isNotBlank() &&
        toolName == MONTHLY_SPENDING_SUMMARY_TOOL_NAME &&
        input.actionOrNull() == SAVE_ACTION

private fun UIMessagePart.Tool.sourceMessageIdsOrNull(): Set<Uuid>? {
    val encoded = metadata?.get(SOURCE_MESSAGE_IDS_METADATA_KEY) as? JsonArray ?: return null
    if (encoded.isEmpty()) return null
    val parsed = encoded.mapNotNull { element ->
        (element as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    }
    return parsed.toSet().takeIf { parsed.size == encoded.size && it.isNotEmpty() }
}

private fun UIMessagePart.Tool.withSourceMessageIds(sourceMessageIds: Set<Uuid>): UIMessagePart.Tool {
    val sourceIdsJson = JsonArray(
        sourceMessageIds
            .map(Uuid::toString)
            .sorted()
            .map(::JsonPrimitive),
    )
    return copy(
        metadata = JsonObject(
            metadata.orEmpty() + (SOURCE_MESSAGE_IDS_METADATA_KEY to sourceIdsJson),
        ),
    )
}

private fun UIMessagePart.Tool.hasSuccessfulMonthlySpendingSaveOutput(): Boolean =
    output
        .asSequence()
        .filterIsInstance<UIMessagePart.Text>()
        .mapNotNull { it.text.jsonObjectOrNull() }
        .any { result ->
            val success = result["success"] as? JsonPrimitive
            val action = result["action"] as? JsonPrimitive
            success != null &&
                !success.isString &&
                success.booleanOrNull == true &&
                action != null &&
                action.isString &&
                action.contentOrNull
                    ?.trim()
                    ?.lowercase(Locale.ROOT) == SAVE_ACTION
        }

private fun String.actionOrNull(): String? =
    jsonObjectOrNull()
        ?.get("action")
        ?.let { it as? JsonPrimitive }
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.trim()
        ?.lowercase(Locale.ROOT)

private fun String.jsonObjectOrNull(): JsonObject? =
    runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull()

private fun String.isLocalFileUri(): Boolean = startsWith(LOCAL_FILE_URI_PREFIX)
