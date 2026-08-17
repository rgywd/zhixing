package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.HealthMetricDAO
import me.rerere.rikkahub.data.db.entity.HealthMetricRecordEntity
import me.rerere.rikkahub.data.model.HealthMetricChatSource
import me.rerere.rikkahub.data.model.HealthMetricDraft
import me.rerere.rikkahub.data.model.HealthMetricSourceType
import me.rerere.rikkahub.data.model.HealthMetricType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMetricRepositoryTest {
    @Test
    fun `chat saves are canonical source-bound and idempotent`() = runBlocking {
        val dao = FakeHealthMetricDao()
        val repository = HealthMetricRepository(dao)
        val source = HealthMetricChatSource(
            conversationId = "conversation-1",
            messageId = "message-1",
            recordedAtEpochMillis = 1_800_000_000_000L,
        )
        val drafts = listOf(
            HealthMetricDraft(HealthMetricType.WEIGHT_KG, "72.40"),
            HealthMetricDraft(HealthMetricType.BODY_FAT_PERCENT, "19.1", 1_799_000_000_000L),
        )

        val first = repository.saveFromChat(drafts, source)
        val repeated = repository.saveFromChat(drafts.reversed(), source)

        assertEquals(2, dao.rows.value.size)
        assertEquals(first.map { it.id }.toSet(), repeated.map { it.id }.toSet())
        assertEquals("72.4", first.first { it.type == HealthMetricType.WEIGHT_KG }.valueDecimal)
        assertTrue(first.all { it.sourceType == HealthMetricSourceType.AI_EXTRACTED_CHAT })
        assertTrue(first.all { it.sourceConversationId == source.conversationId })
        assertTrue(first.all { it.sourceMessageId == source.messageId })
    }
}

private class FakeHealthMetricDao : HealthMetricDAO {
    val rows = MutableStateFlow<List<HealthMetricRecordEntity>>(emptyList())

    override fun observeAll(): Flow<List<HealthMetricRecordEntity>> = rows

    override suspend fun getRecent(limit: Int): List<HealthMetricRecordEntity> = rows.value
        .sortedWith(
            compareByDescending<HealthMetricRecordEntity> { it.effectiveAtEpochMillis }
                .thenByDescending { it.recordedAtEpochMillis }
                .thenByDescending { it.id },
        )
        .take(limit)

    override suspend fun getByIds(ids: List<String>): List<HealthMetricRecordEntity> =
        rows.value.filter { it.id in ids }

    override suspend fun insertIgnore(records: List<HealthMetricRecordEntity>): List<Long> {
        val existing = rows.value.associateBy(HealthMetricRecordEntity::id).toMutableMap()
        val results = records.map { record ->
            if (record.id in existing) {
                -1L
            } else {
                existing[record.id] = record
                existing.size.toLong()
            }
        }
        rows.value = existing.values.toList()
        return results
    }

    override suspend fun deleteByIds(ids: List<String>): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot { it.id in ids }
        return before - rows.value.size
    }
}
