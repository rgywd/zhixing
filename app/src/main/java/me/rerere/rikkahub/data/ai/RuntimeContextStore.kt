package me.rerere.rikkahub.data.ai

import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.db.dao.AssistantTaskDAO
import me.rerere.rikkahub.data.db.entity.AssistantRuntimeContextEntity

class RuntimeContextStore(
    private val dao: AssistantTaskDAO,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun attach(
        context: UIMessageAnnotation.RuntimeContext,
        conversationId: String,
        messageId: String,
    ): UIMessageAnnotation.RuntimeContext {
        val id = context.contextId ?: UUID.randomUUID().toString()
        val attached = context.copy(contextId = id)
        dao.insertRuntimeContext(
            AssistantRuntimeContextEntity(
                id = id,
                conversationId = conversationId,
                messageId = messageId,
                kind = attached.kind.take(40),
                title = attached.title.take(120),
                summary = attached.summary.take(500),
                recommendation = attached.recommendation?.take(500),
                generatedAt = attached.generatedAtEpochMillis,
                validUntil = attached.validUntilEpochMillis,
                privacyScopeJson = json.encodeToString(attached.privacyScope),
                evidenceJson = json.encodeToString(attached.evidence),
                createdAt = clock(),
            )
        )
        return attached
    }

    suspend fun delete(contextId: String) {
        dao.deleteRuntimeContext(contextId)
    }
}
