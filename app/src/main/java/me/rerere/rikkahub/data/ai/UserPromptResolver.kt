package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantUserPromptSource
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationUserPromptSnapshot
import me.rerere.rikkahub.data.model.UserPromptSnapshotSource
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.AssistantUserPromptDocument

class UserPromptResolver(
    private val workspaceRepository: WorkspaceRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun snapshotForFirstUserMessage(
        conversation: Conversation,
        assistant: Assistant,
    ): ConversationUserPromptSnapshot {
        conversation.userPromptSnapshot?.let { return it }

        // Existing/imported conversations must not silently adopt a vault prompt after upgrade.
        if (conversation.currentMessages.any { it.role == MessageRole.USER }) {
            return resolveUserPromptSnapshot(conversation, assistant, document = null, capturedAt = now())
        }

        val workspaceId = assistant.workspaceId?.toString()
        if (assistant.userPromptSource == AssistantUserPromptSource.KNOWLEDGE_VAULT && workspaceId != null) {
            val workspace = workspaceRepository.getById(workspaceId)
            val vaultReady = workspace != null && workspaceRepository.isKnowledgeVaultInitialized(workspaceId)
            if (vaultReady) {
                val document = workspaceRepository.readAssistantUserPrompt(
                    id = workspaceId,
                    assistantId = assistant.id.toString(),
                )
                if (document != null) {
                    return resolveUserPromptSnapshot(conversation, assistant, document, now())
                }
            }
        }

        return resolveUserPromptSnapshot(conversation, assistant, document = null, capturedAt = now())
    }
}

internal fun resolveUserPromptSnapshot(
    conversation: Conversation,
    assistant: Assistant,
    document: AssistantUserPromptDocument?,
    capturedAt: Long,
): ConversationUserPromptSnapshot {
    conversation.userPromptSnapshot?.let { return it }
    val canUseVault = conversation.currentMessages.none { it.role == MessageRole.USER } &&
        assistant.userPromptSource == AssistantUserPromptSource.KNOWLEDGE_VAULT &&
        document != null
    return if (canUseVault) {
        ConversationUserPromptSnapshot(
            content = document.content,
            source = UserPromptSnapshotSource.KNOWLEDGE_VAULT,
            sourcePath = document.path,
            revision = document.revision,
            capturedAtEpochMillis = capturedAt,
        )
    } else {
        ConversationUserPromptSnapshot(
            content = assistant.systemPrompt,
            source = UserPromptSnapshotSource.ASSISTANT_SETTING,
            capturedAtEpochMillis = capturedAt,
        )
    }
}

internal fun effectiveUserPrompt(
    assistant: Assistant,
    conversationSystemPrompt: String?,
    snapshot: ConversationUserPromptSnapshot?,
): String = if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
    conversationSystemPrompt
} else {
    snapshot?.content ?: assistant.systemPrompt
}
