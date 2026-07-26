package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MonthlyLedgerDAO
import me.rerere.rikkahub.data.db.entity.toEntity
import me.rerere.rikkahub.data.db.entity.toMonthlyLedgerSummary
import me.rerere.rikkahub.data.model.MonthlyLedgerAggregationMode
import me.rerere.rikkahub.data.model.MonthlyLedgerChannelDraft
import me.rerere.rikkahub.data.model.MonthlyLedgerChannelSummary
import me.rerere.rikkahub.data.model.MonthlyLedgerSummary
import me.rerere.rikkahub.data.model.MonthlyLedgerSummaryDraft
import java.time.YearMonth

class MonthlyLedgerRepository(
    private val dao: MonthlyLedgerDAO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observeMonth(month: String): Flow<List<MonthlyLedgerSummary>> {
        validateMonth(month)
        return dao.observeMonth(month).map { rows ->
            rows.map { it.toMonthlyLedgerSummary() }
        }
    }

    suspend fun getMonth(month: String): List<MonthlyLedgerSummary> {
        validateMonth(month)
        return dao.getMonth(month).map { it.toMonthlyLedgerSummary() }
    }

    suspend fun replace(draft: MonthlyLedgerSummaryDraft): MonthlyLedgerSummary {
        validateDraft(draft)
        val id = summaryId(draft.month, draft.currency)
        val summary = MonthlyLedgerSummary(
            id = id,
            month = draft.month,
            currency = draft.currency,
            minorUnit = draft.minorUnit,
            expenseMinor = draft.expenseMinor,
            incomeMinor = draft.incomeMinor,
            aggregationMode = draft.aggregationMode,
            coverage = draft.coverage,
            confidencePercent = draft.confidencePercent,
            warnings = draft.warnings,
            channels = draft.channels
                .map { it.toSummary() }
                .sortedBy { it.channelKey },
            updatedAt = now(),
        )
        dao.replaceSummary(
            summary = summary.toEntity(),
            channels = summary.channels.map { it.toEntity(summary.id) },
        )
        return summary
    }

    suspend fun delete(month: String, currency: String? = null) {
        validateMonth(month)
        if (currency == null) {
            dao.deleteMonth(month)
        } else {
            validateCurrency(currency)
            dao.deleteMonthCurrency(month, currency)
        }
    }

    private fun validateDraft(draft: MonthlyLedgerSummaryDraft) {
        validateMonth(draft.month)
        require(YearMonth.parse(draft.month) <= YearMonth.now()) { "不能保存未来月份的收支汇总" }
        validateCurrency(draft.currency)
        require(draft.minorUnit in 0..6) { "货币小数位必须在 0 到 6 之间" }
        require(draft.expenseMinor >= 0) { "月度支出不能为负数" }
        require(draft.incomeMinor >= 0) { "月度收入不能为负数" }
        validateConfidence(draft.confidencePercent, "月度汇总")
        require(draft.channels.isNotEmpty()) { "月度汇总至少需要一个渠道" }

        val channelKeys = mutableSetOf<String>()
        draft.channels.forEach { channel ->
            require(CHANNEL_KEY_PATTERN.matches(channel.channelKey)) {
                "渠道 key 必须以小写字母或数字开头，且只能包含小写字母、数字、下划线和连字符"
            }
            require(channelKeys.add(channel.channelKey)) { "同一月度汇总中渠道 key 不能重复" }
            require(channel.channelName.isNotBlank()) { "渠道名称不能为空" }
            require(channel.expenseMinor >= 0) { "渠道支出不能为负数" }
            require(channel.incomeMinor >= 0) { "渠道收入不能为负数" }
            validateConfidence(channel.confidencePercent, "渠道 ${channel.channelKey}")
        }

        if (draft.aggregationMode == MonthlyLedgerAggregationMode.RAW_CHANNEL_SUM) {
            require(draft.expenseMinor == sumExactly(draft.channels.map { it.expenseMinor }, "渠道支出")) {
                "RAW_CHANNEL_SUM 的月度支出必须等于渠道支出之和"
            }
            require(draft.incomeMinor == sumExactly(draft.channels.map { it.incomeMinor }, "渠道收入")) {
                "RAW_CHANNEL_SUM 的月度收入必须等于渠道收入之和"
            }
        }
    }

    private fun validateMonth(month: String) {
        require(MONTH_PATTERN.matches(month)) { "月份必须使用 YYYY-MM 格式" }
    }

    private fun validateCurrency(currency: String) {
        require(CURRENCY_PATTERN.matches(currency)) { "币种必须使用三个大写字母的 ISO 4217 代码" }
    }

    private fun validateConfidence(confidencePercent: Int, label: String) {
        require(confidencePercent in 0..100) { "$label 置信度必须在 0 到 100 之间" }
    }

    private fun sumExactly(values: List<Long>, label: String): Long =
        try {
            values.fold(0L, Math::addExact)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("${label}总和超出 Long 范围")
        }

    private fun summaryId(month: String, currency: String): String = "$month|$currency"

    private fun MonthlyLedgerChannelDraft.toSummary(): MonthlyLedgerChannelSummary =
        MonthlyLedgerChannelSummary(
            channelKey = channelKey,
            channelName = channelName.trim(),
            expenseMinor = expenseMinor,
            incomeMinor = incomeMinor,
            coverage = coverage,
            confidencePercent = confidencePercent,
            warnings = warnings,
        )

    private companion object {
        val MONTH_PATTERN = Regex("""\d{4}-(0[1-9]|1[0-2])""")
        val CURRENCY_PATTERN = Regex("""[A-Z]{3}""")
        val CHANNEL_KEY_PATTERN = Regex("""[a-z0-9][a-z0-9_-]{0,47}""")
    }
}
