package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MonthlyLedgerDAO
import me.rerere.rikkahub.data.db.entity.MonthlyLedgerChannelEntity
import me.rerere.rikkahub.data.db.entity.MonthlyLedgerSummaryEntity
import me.rerere.rikkahub.data.db.entity.MonthlyLedgerSummaryWithChannelsEntity
import me.rerere.rikkahub.data.model.MonthlyLedgerAggregationMode
import me.rerere.rikkahub.data.model.MonthlyLedgerChannelDraft
import me.rerere.rikkahub.data.model.MonthlyLedgerCoverage
import me.rerere.rikkahub.data.model.MonthlyLedgerSummaryDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyLedgerRepositoryTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `replace persists a deterministic summary and round trips warnings`() = runBlocking {
        val dao = FakeMonthlyLedgerDao()
        val repository = MonthlyLedgerRepository(dao) { now }

        val saved = repository.replace(
            draft(
                warnings = listOf("信用卡渠道可能与支付平台重复"),
                channels = listOf(
                    channel(
                        key = "wechat",
                        name = " 微信支付 ",
                        expense = 2_000,
                        income = 100,
                        warnings = listOf("仅覆盖 1 至 28 日"),
                    ),
                    channel("alipay", "支付宝", expense = 3_000, income = 200),
                ),
            )
        )

        assertEquals("2026-07|CNY", saved.id)
        assertEquals(now, saved.updatedAt)
        assertEquals(-4_700L, saved.netMinor)
        assertEquals("微信支付", saved.channels.single { it.channelKey == "wechat" }.channelName)
        assertEquals(-1_900L, saved.channels.single { it.channelKey == "wechat" }.netMinor)

        val readBack = repository.getMonth("2026-07").single()
        assertEquals(saved, readBack)
        assertEquals(listOf("信用卡渠道可能与支付平台重复"), readBack.warnings)
        assertEquals(listOf("仅覆盖 1 至 28 日"), readBack.channels.last().warnings)
        assertEquals(listOf("alipay", "wechat"), readBack.channels.map { it.channelKey })
    }

    @Test
    fun `replace removes stale channels without affecting other currencies`() = runBlocking {
        val dao = FakeMonthlyLedgerDao()
        val repository = MonthlyLedgerRepository(dao) { now }
        repository.replace(
            draft(
                channels = listOf(
                    channel("wechat", "微信支付", expense = 2_000, income = 100),
                    channel("alipay", "支付宝", expense = 3_000, income = 200),
                ),
            )
        )
        repository.replace(
            draft(
                currency = "USD",
                minorUnit = 2,
                expense = 50,
                income = 0,
                channels = listOf(channel("visa", "Visa", expense = 50, income = 0)),
            )
        )

        val replaced = repository.replace(
            draft(
                expense = 1_500,
                income = 50,
                channels = listOf(channel("wechat", "微信支付", expense = 1_500, income = 50)),
            )
        )

        assertEquals(listOf("wechat"), replaced.channels.map { it.channelKey })
        assertEquals(
            listOf("CNY", "USD"),
            repository.observeMonth("2026-07").first().map { it.currency },
        )
        assertEquals(
            listOf("wechat"),
            repository.getMonth("2026-07").first { it.currency == "CNY" }.channels.map { it.channelKey },
        )
    }

    @Test
    fun `raw channel sum requires exact expense and income totals`() = runBlocking {
        val repository = MonthlyLedgerRepository(FakeMonthlyLedgerDao()) { now }

        val expenseMismatch = runCatching {
            repository.replace(
                draft(
                    aggregationMode = MonthlyLedgerAggregationMode.RAW_CHANNEL_SUM,
                    expense = 4_999,
                    income = 300,
                    channels = listOf(
                        channel("wechat", "微信支付", expense = 2_000, income = 100),
                        channel("alipay", "支付宝", expense = 3_000, income = 200),
                    ),
                )
            )
        }
        val incomeMismatch = runCatching {
            repository.replace(
                draft(
                    aggregationMode = MonthlyLedgerAggregationMode.RAW_CHANNEL_SUM,
                    expense = 5_000,
                    income = 299,
                    channels = listOf(
                        channel("wechat", "微信支付", expense = 2_000, income = 100),
                        channel("alipay", "支付宝", expense = 3_000, income = 200),
                    ),
                )
            )
        }

        assertTrue(expenseMismatch.isFailure)
        assertTrue(incomeMismatch.isFailure)
    }

    @Test
    fun `deduped estimate permits totals that differ from channel sums`() = runBlocking {
        val repository = MonthlyLedgerRepository(FakeMonthlyLedgerDao()) { now }

        val saved = repository.replace(
            draft(
                expense = 4_200,
                income = 250,
                aggregationMode = MonthlyLedgerAggregationMode.DEDUPED_ESTIMATE,
                channels = listOf(
                    channel("wechat", "微信支付", expense = 2_000, income = 100),
                    channel("credit_card", "信用卡", expense = 3_000, income = 200),
                ),
            )
        )

        assertEquals(4_200L, saved.expenseMinor)
        assertEquals(5_000L, saved.channels.sumOf { it.expenseMinor })
        assertEquals(250L, saved.incomeMinor)
        assertEquals(300L, saved.channels.sumOf { it.incomeMinor })
    }

    @Test
    fun `replace rejects invalid boundary values before writing`() = runBlocking {
        val invalidDrafts = listOf(
            draft(month = "2026-13"),
            draft(month = "9999-12"),
            draft(currency = "cny"),
            draft(minorUnit = 7),
            draft(expense = -1),
            draft(confidence = 101),
            draft(channels = emptyList()),
            draft(channels = listOf(channel("WeChat", "微信支付"))),
            draft(channels = listOf(channel("wechat", " "))),
            draft(
                channels = listOf(
                    channel("wechat", "微信支付"),
                    channel("wechat", "微信支付重复"),
                )
            ),
            draft(channels = listOf(channel("wechat", "微信支付", confidence = -1))),
        )

        invalidDrafts.forEach { invalid ->
            val dao = FakeMonthlyLedgerDao()
            val result = runCatching { MonthlyLedgerRepository(dao) { now }.replace(invalid) }
            assertTrue("Expected validation failure for $invalid", result.isFailure)
            assertTrue(dao.summaries.isEmpty())
        }
    }

    @Test
    fun `delete can target one currency or the whole month`() = runBlocking {
        val repository = MonthlyLedgerRepository(FakeMonthlyLedgerDao()) { now }
        repository.replace(draft())
        repository.replace(
            draft(
                currency = "USD",
                expense = 50,
                income = 0,
                channels = listOf(channel("visa", "Visa", expense = 50, income = 0)),
            )
        )

        repository.delete("2026-07", "USD")
        assertEquals(listOf("CNY"), repository.getMonth("2026-07").map { it.currency })

        repository.delete("2026-07")
        assertTrue(repository.getMonth("2026-07").isEmpty())
    }

    private fun draft(
        month: String = "2026-07",
        currency: String = "CNY",
        minorUnit: Int = 2,
        expense: Long = 5_000,
        income: Long = 300,
        aggregationMode: MonthlyLedgerAggregationMode = MonthlyLedgerAggregationMode.RAW_CHANNEL_SUM,
        coverage: MonthlyLedgerCoverage = MonthlyLedgerCoverage.FULL,
        confidence: Int = 92,
        warnings: List<String> = emptyList(),
        channels: List<MonthlyLedgerChannelDraft> = listOf(
            channel("wechat", "微信支付", expense = 2_000, income = 100),
            channel("alipay", "支付宝", expense = 3_000, income = 200),
        ),
    ) = MonthlyLedgerSummaryDraft(
        month = month,
        currency = currency,
        minorUnit = minorUnit,
        expenseMinor = expense,
        incomeMinor = income,
        aggregationMode = aggregationMode,
        coverage = coverage,
        confidencePercent = confidence,
        warnings = warnings,
        channels = channels,
    )

    private fun channel(
        key: String,
        name: String,
        expense: Long = 0,
        income: Long = 0,
        confidence: Int = 90,
        warnings: List<String> = emptyList(),
    ) = MonthlyLedgerChannelDraft(
        channelKey = key,
        channelName = name,
        expenseMinor = expense,
        incomeMinor = income,
        coverage = MonthlyLedgerCoverage.FULL,
        confidencePercent = confidence,
        warnings = warnings,
    )
}

private class FakeMonthlyLedgerDao : MonthlyLedgerDAO {
    val summaries = linkedMapOf<String, MonthlyLedgerSummaryEntity>()
    private val channels = linkedMapOf<Pair<String, String>, MonthlyLedgerChannelEntity>()

    override fun observeMonth(month: String): Flow<List<MonthlyLedgerSummaryWithChannelsEntity>> =
        flowOf(rows(month))

    override suspend fun getMonth(month: String): List<MonthlyLedgerSummaryWithChannelsEntity> = rows(month)

    override suspend fun upsertSummary(summary: MonthlyLedgerSummaryEntity) {
        summaries[summary.id] = summary
    }

    override suspend fun deleteChannels(summaryId: String) {
        channels.keys.removeAll { it.first == summaryId }
    }

    override suspend fun upsertChannels(channels: List<MonthlyLedgerChannelEntity>) {
        channels.forEach { channel ->
            this.channels[channel.summaryId to channel.channelKey] = channel
        }
    }

    override suspend fun deleteMonth(month: String) {
        val ids = summaries.values.filter { it.month == month }.map { it.id }.toSet()
        summaries.keys.removeAll(ids)
        channels.keys.removeAll { it.first in ids }
    }

    override suspend fun deleteMonthCurrency(month: String, currency: String) {
        val ids = summaries.values
            .filter { it.month == month && it.currency == currency }
            .map { it.id }
            .toSet()
        summaries.keys.removeAll(ids)
        channels.keys.removeAll { it.first in ids }
    }

    private fun rows(month: String): List<MonthlyLedgerSummaryWithChannelsEntity> =
        summaries.values
            .filter { it.month == month }
            .sortedBy { it.currency }
            .map { summary ->
                MonthlyLedgerSummaryWithChannelsEntity(
                    summary = summary,
                    channels = channels.values.filter { it.summaryId == summary.id },
                )
            }
}
