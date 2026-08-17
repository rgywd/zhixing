package me.rerere.rikkahub.data.repository

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.HealthMetricDAO
import me.rerere.rikkahub.data.db.entity.HealthMetricRecordEntity
import me.rerere.rikkahub.data.model.HealthMetricChatSource
import me.rerere.rikkahub.data.model.HealthMetricDraft
import me.rerere.rikkahub.data.model.HealthMetricRecord
import me.rerere.rikkahub.data.model.HealthMetricSourceType
import me.rerere.rikkahub.data.model.HealthMetricType

class HealthMetricRepository(
    private val dao: HealthMetricDAO,
) {
    fun observeRecords(): Flow<List<HealthMetricRecord>> =
        dao.observeAll().map { entities -> entities.mapNotNull(HealthMetricRecordEntity::toModel) }

    suspend fun getRecent(
        types: Set<HealthMetricType>? = null,
        limit: Int = DEFAULT_QUERY_LIMIT,
    ): List<HealthMetricRecord> = dao.getRecent(MAX_QUERY_SCAN)
        .asSequence()
        .mapNotNull(HealthMetricRecordEntity::toModel)
        .filter { types == null || it.type in types }
        .take(limit.coerceIn(1, MAX_QUERY_LIMIT))
        .toList()

    suspend fun saveFromChat(
        drafts: List<HealthMetricDraft>,
        source: HealthMetricChatSource,
    ): List<HealthMetricRecord> {
        require(drafts.isNotEmpty()) { "at least one health metric is required" }
        val entities = drafts.distinctBy { Triple(it.type, it.valueDecimal, it.observedAtEpochMillis) }.map { draft ->
            val normalized = draft.type.normalizeValue(draft.valueDecimal)
            HealthMetricRecordEntity(
                id = stableChatRecordId(
                    conversationId = source.conversationId,
                    messageId = source.messageId,
                    type = draft.type,
                    valueDecimal = normalized,
                    observedAtEpochMillis = draft.observedAtEpochMillis,
                ),
                metricType = draft.type.name,
                valueDecimal = normalized,
                unit = draft.type.canonicalUnit,
                observedAtEpochMillis = draft.observedAtEpochMillis,
                recordedAtEpochMillis = source.recordedAtEpochMillis,
                effectiveAtEpochMillis = draft.observedAtEpochMillis ?: source.recordedAtEpochMillis,
                sourceType = HealthMetricSourceType.AI_EXTRACTED_CHAT.name,
                sourceConversationId = source.conversationId,
                sourceMessageId = source.messageId,
            )
        }
        dao.insertIgnore(entities)
        val stored = dao.getByIds(entities.map(HealthMetricRecordEntity::id))
            .mapNotNull(HealthMetricRecordEntity::toModel)
            .associateBy(HealthMetricRecord::id)
        return entities.mapNotNull { stored[it.id] }
    }

    suspend fun deleteRecords(ids: List<String>): Int =
        if (ids.isEmpty()) 0 else dao.deleteByIds(ids.distinct().take(MAX_DELETE_COUNT))

    private fun stableChatRecordId(
        conversationId: String,
        messageId: String,
        type: HealthMetricType,
        valueDecimal: String,
        observedAtEpochMillis: Long?,
    ): String {
        val payload = listOf(
            conversationId,
            messageId,
            type.name,
            valueDecimal,
            observedAtEpochMillis?.toString().orEmpty(),
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "health-${digest.take(32)}"
    }

    private companion object {
        const val DEFAULT_QUERY_LIMIT = 50
        const val MAX_QUERY_LIMIT = 100
        const val MAX_QUERY_SCAN = 2_000
        const val MAX_DELETE_COUNT = 100
    }
}

private fun HealthMetricRecordEntity.toModel(): HealthMetricRecord? {
    val type = runCatching { HealthMetricType.valueOf(metricType) }.getOrNull() ?: return null
    val source = runCatching { HealthMetricSourceType.valueOf(sourceType) }.getOrNull() ?: return null
    return HealthMetricRecord(
        id = id,
        type = type,
        valueDecimal = valueDecimal,
        unit = unit,
        observedAtEpochMillis = observedAtEpochMillis,
        recordedAtEpochMillis = recordedAtEpochMillis,
        sourceType = source,
        sourceConversationId = sourceConversationId,
        sourceMessageId = sourceMessageId,
    )
}
