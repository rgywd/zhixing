package me.rerere.rikkahub.data.model

enum class MonthlyLedgerCoverage {
    FULL,
    PARTIAL,
    UNKNOWN,
}

enum class MonthlyLedgerAggregationMode {
    RAW_CHANNEL_SUM,
    DEDUPED_ESTIMATE,
}

data class MonthlyLedgerChannelSummary(
    val channelKey: String,
    val channelName: String,
    val expenseMinor: Long,
    val incomeMinor: Long,
    val coverage: MonthlyLedgerCoverage,
    val confidencePercent: Int,
    val warnings: List<String>,
) {
    val netMinor: Long
        get() = incomeMinor - expenseMinor
}

data class MonthlyLedgerSummary(
    val id: String,
    val month: String,
    val currency: String,
    val minorUnit: Int,
    val expenseMinor: Long,
    val incomeMinor: Long,
    val aggregationMode: MonthlyLedgerAggregationMode,
    val coverage: MonthlyLedgerCoverage,
    val confidencePercent: Int,
    val warnings: List<String>,
    val channels: List<MonthlyLedgerChannelSummary>,
    val updatedAt: Long,
) {
    val netMinor: Long
        get() = incomeMinor - expenseMinor
}

data class MonthlyLedgerChannelDraft(
    val channelKey: String,
    val channelName: String,
    val expenseMinor: Long,
    val incomeMinor: Long,
    val coverage: MonthlyLedgerCoverage = MonthlyLedgerCoverage.UNKNOWN,
    val confidencePercent: Int,
    val warnings: List<String> = emptyList(),
)

data class MonthlyLedgerSummaryDraft(
    val month: String,
    val currency: String,
    val minorUnit: Int,
    val expenseMinor: Long,
    val incomeMinor: Long,
    val aggregationMode: MonthlyLedgerAggregationMode,
    val coverage: MonthlyLedgerCoverage = MonthlyLedgerCoverage.UNKNOWN,
    val confidencePercent: Int,
    val warnings: List<String> = emptyList(),
    val channels: List<MonthlyLedgerChannelDraft>,
)
