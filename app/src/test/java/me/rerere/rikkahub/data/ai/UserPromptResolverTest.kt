package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantUserPromptSource
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationUserPromptSnapshot
import me.rerere.rikkahub.data.model.UserPromptSnapshotSource
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.workspace.AssistantUserPromptDocument
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class UserPromptResolverTest {
    private val document = AssistantUserPromptDocument(
        path = "vault/99_系统/提示词/助手/assistant.md",
        content = "vault prompt",
        revision = "revision-1",
    )

    @Test
    fun newConversationUsesVaultDocumentIncludingExplicitEmptyContent() {
        val assistant = assistant().copy(userPromptSource = AssistantUserPromptSource.KNOWLEDGE_VAULT)
        val snapshot = resolveUserPromptSnapshot(
            conversation = conversation(),
            assistant = assistant,
            document = document.copy(content = ""),
            capturedAt = 10L,
        )

        assertEquals("", snapshot.content)
        assertEquals(UserPromptSnapshotSource.KNOWLEDGE_VAULT, snapshot.source)
        assertEquals(document.path, snapshot.sourcePath)
    }

    @Test
    fun missingDocumentFallsBackToAssistantSetting() {
        val snapshot = resolveUserPromptSnapshot(
            conversation = conversation(),
            assistant = assistant().copy(userPromptSource = AssistantUserPromptSource.KNOWLEDGE_VAULT),
            document = null,
            capturedAt = 10L,
        )

        assertEquals("app prompt", snapshot.content)
        assertEquals(UserPromptSnapshotSource.ASSISTANT_SETTING, snapshot.source)
    }

    @Test
    fun existingConversationDoesNotSilentlyAdoptVaultAfterUpgrade() {
        val snapshot = resolveUserPromptSnapshot(
            conversation = conversation(messages = listOf(UIMessage.user("old message"))),
            assistant = assistant().copy(userPromptSource = AssistantUserPromptSource.KNOWLEDGE_VAULT),
            document = document,
            capturedAt = 10L,
        )

        assertEquals("app prompt", snapshot.content)
        assertEquals(UserPromptSnapshotSource.ASSISTANT_SETTING, snapshot.source)
    }

    @Test
    fun existingSnapshotIsStableAndConversationOverrideOnlyReplacesUserLayer() {
        val frozen = ConversationUserPromptSnapshot(
            content = "frozen",
            source = UserPromptSnapshotSource.ASSISTANT_SETTING,
            capturedAtEpochMillis = 1L,
        )
        val assistant = assistant().copy(allowConversationSystemPrompt = true)
        val snapshot = resolveUserPromptSnapshot(
            conversation = conversation().copy(userPromptSnapshot = frozen),
            assistant = assistant,
            document = document,
            capturedAt = 10L,
        )

        assertEquals(frozen, snapshot)
        assertEquals("conversation", effectiveUserPrompt(assistant, "conversation", snapshot))
        assertEquals("frozen", effectiveUserPrompt(assistant.copy(allowConversationSystemPrompt = false), "conversation", snapshot))
    }

    @Test
    fun explicitlyEmptyFrozenPromptDoesNotFallBackToAssistantSetting() {
        val frozen = ConversationUserPromptSnapshot(
            content = "",
            source = UserPromptSnapshotSource.KNOWLEDGE_VAULT,
            capturedAtEpochMillis = 1L,
        )

        assertEquals("", effectiveUserPrompt(assistant(), null, frozen))
    }

    private fun assistant() = Assistant(
        id = Uuid.random(),
        systemPrompt = "app prompt",
    )

    private fun conversation(messages: List<UIMessage> = emptyList()) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
    )
}
