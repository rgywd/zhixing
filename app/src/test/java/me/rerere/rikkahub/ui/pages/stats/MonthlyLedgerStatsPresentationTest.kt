package me.rerere.rikkahub.ui.pages.stats

import java.time.YearMonth
import java.util.Locale
import me.rerere.rikkahub.data.model.MonthlyLedgerAggregationMode
import me.rerere.rikkahub.data.model.MonthlyLedgerChannelSummary
import me.rerere.rikkahub.data.model.MonthlyLedgerCoverage
import me.rerere.rikkahub.data.model.MonthlyLedgerSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MonthlyLedgerStatsPresentationTest {
    @Test
    fun `month total stays persisted instead of being rebuilt from overlapping channels`() {
        val state = buildMonthlyLedgerStatsUiState(
            month = YearMonth.of(2026, 7),
            summaries = listOf(
                summary(
                    expenseMinor = 8_000,
                    incomeMinor = 1_000,
                    aggregationMode = MonthlyLedgerAggregationMode.DEDUPED_ESTIMATE,
                    channels = listOf(
                        channel("wechat", "微信", expenseMinor = 6_000),
                        channel("alipay", "支付宝", expenseMinor = 6_000),
                    ),
                )
            ),
        )

        val currency = state.currencies.single()
        val rawChannelExpense = currency.channels.sumOf { it.expenseMinor }
        assertEquals(8_000L, currency.expenseMinor)
        assertEquals(-7_000L, currency.netMinor)
        assertEquals(12_000L, rawChannelExpense)
        assertNotEquals(rawChannelExpense, currency.expenseMinor)
    }

    @Test
    fun `presentation filters the selected month and orders currencies and channels`() {
        val state = buildMonthlyLedgerStatsUiState(
            month = YearMonth.of(2026, 7),
            summaries = listOf(
                summary(
                    month = "2026-07",
                    currency = "USD",
                    channels = listOf(channel("card", "Card", 100)),
                ),
                summary(
                    month = "2026-06",
                    currency = "CNY",
                    channels = listOf(channel("old", "Old", 500)),
                ),
                summary(
                    month = "2026-07",
                    currency = "CNY",
                    channels = listOf(
                        channel("wechat", "微信", 300),
                        channel("alipay", "支付宝", 700),
                    ),
                ),
            ),
        )

        assertEquals(listOf("CNY", "USD"), state.currencies.map { it.currency })
        assertEquals(
            listOf("alipay", "wechat"),
            state.currencies.first().channels.map { it.channelKey },
        )
    }

    @Test
    fun `amount formatting keeps exact minor units without floating point conversion`() {
        assertEquals(
            "CNY 1,234,567,890,123.45",
            formatLedgerAmount("CNY", 2, 123_456_789_012_345, Locale.US),
        )
        assertEquals(
            "CNY -92,233,720,368,547,758.08",
            formatLedgerAmount("CNY", 2, Long.MIN_VALUE, Locale.US),
        )
        assertEquals(
            "JPY 1,234",
            formatLedgerAmount("JPY", 0, 1_234, Locale.US),
        )
    }

    @Test
    fun `negative minor unit is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            formatLedgerAmount("CNY", -1, 100, Locale.US)
        }
    }

    @Test
    fun `next month selection never advances beyond the current month`() {
        val current = YearMonth.of(2026, 7)
        assertEquals(current, nextLedgerMonth(YearMonth.of(2026, 6), current))
        assertEquals(current, nextLedgerMonth(current, current))
    }

    private fun summary(
        month: String = "2026-07",
        currency: String = "CNY",
        expenseMinor: Long = 0,
        incomeMinor: Long = 0,
        aggregationMode: MonthlyLedgerAggregationMode = MonthlyLedgerAggregationMode.RAW_CHANNEL_SUM,
        channels: List<MonthlyLedgerChannelSummary>,
    ) = MonthlyLedgerSummary(
        id = "$month:$currency",
        month = month,
        currency = currency,
        minorUnit = 2,
        expenseMinor = expenseMinor,
        incomeMinor = incomeMinor,
        aggregationMode = aggregationMode,
        coverage = MonthlyLedgerCoverage.FULL,
        confidencePercent = 95,
        warnings = emptyList(),
        channels = channels,
        updatedAt = 1_800_000_000_000,
    )

    private fun channel(
        key: String,
        name: String,
        expenseMinor: Long,
        incomeMinor: Long = 0,
    ) = MonthlyLedgerChannelSummary(
        channelKey = key,
        channelName = name,
        expenseMinor = expenseMinor,
        incomeMinor = incomeMinor,
        coverage = MonthlyLedgerCoverage.FULL,
        confidencePercent = 90,
        warnings = emptyList(),
    )
}
