package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Relation
import me.rerere.rikkahub.data.model.MonthlyLedgerAggregationMode
import me.rerere.rikkahub.data.model.MonthlyLedgerChannelSummary
import me.rerere.rikkahub.data.model.MonthlyLedgerCoverage
import me.rerere.rikkahub.data.model.MonthlyLedgerSummary
import me.rerere.rikkahub.utils.JsonInstant

@Entity(
    tableName = "monthly_ledger_summaries",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["month", "currency"], unique = true),
    ],
)
data class MonthlyLedgerSummaryEntity(
    val id: String,
    val month: String,
    val currency: String,
    @ColumnInfo("minor_unit")
    val minorUnit: Int,
    @ColumnInfo("expense_minor")
    val expenseMinor: Long,
    @ColumnInfo("income_minor")
    val incomeMinor: Long,
    @ColumnInfo("aggregation_mode")
    val aggregationMode: String,
    val coverage: String,
    @ColumnInfo("confidence_percent")
    val confidencePercent: Int,
    @ColumnInfo("warnings_json")
    val warningsJson: String,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "monthly_ledger_channels",
    primaryKeys = ["summary_id", "channel_key"],
    foreignKeys = [
        ForeignKey(
            entity = MonthlyLedgerSummaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["summary_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["summary_id"]),
    ],
)
data class MonthlyLedgerChannelEntity(
    @ColumnInfo("summary_id")
    val summaryId: String,
    @ColumnInfo("channel_key")
    val channelKey: String,
    @ColumnInfo("channel_name")
    val channelName: String,
    @ColumnInfo("expense_minor")
    val expenseMinor: Long,
    @ColumnInfo("income_minor")
    val incomeMinor: Long,
    val coverage: String,
    @ColumnInfo("confidence_percent")
    val confidencePercent: Int,
    @ColumnInfo("warnings_json")
    val warningsJson: String,
)

data class MonthlyLedgerSummaryWithChannelsEntity(
    @Embedded
    val summary: MonthlyLedgerSummaryEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "summary_id",
    )
    val channels: List<MonthlyLedgerChannelEntity>,
)

fun MonthlyLedgerSummaryWithChannelsEntity.toMonthlyLedgerSummary(): MonthlyLedgerSummary =
    MonthlyLedgerSummary(
        id = summary.id,
        month = summary.month,
        currency = summary.currency,
        minorUnit = summary.minorUnit,
        expenseMinor = summary.expenseMinor,
        incomeMinor = summary.incomeMinor,
        aggregationMode = runCatching {
            MonthlyLedgerAggregationMode.valueOf(summary.aggregationMode)
        }.getOrDefault(MonthlyLedgerAggregationMode.DEDUPED_ESTIMATE),
        coverage = runCatching {
            MonthlyLedgerCoverage.valueOf(summary.coverage)
        }.getOrDefault(MonthlyLedgerCoverage.UNKNOWN),
        confidencePercent = summary.confidencePercent,
        warnings = decodeMonthlyLedgerWarnings(summary.warningsJson),
        channels = channels
            .map(MonthlyLedgerChannelEntity::toMonthlyLedgerChannelSummary)
            .sortedBy(MonthlyLedgerChannelSummary::channelKey),
        updatedAt = summary.updatedAt,
    )

fun MonthlyLedgerSummary.toEntity(): MonthlyLedgerSummaryEntity =
    MonthlyLedgerSummaryEntity(
        id = id,
        month = month,
        currency = currency,
        minorUnit = minorUnit,
        expenseMinor = expenseMinor,
        incomeMinor = incomeMinor,
        aggregationMode = aggregationMode.name,
        coverage = coverage.name,
        confidencePercent = confidencePercent,
        warningsJson = JsonInstant.encodeToString(warnings),
        updatedAt = updatedAt,
    )

fun MonthlyLedgerChannelSummary.toEntity(summaryId: String): MonthlyLedgerChannelEntity =
    MonthlyLedgerChannelEntity(
        summaryId = summaryId,
        channelKey = channelKey,
        channelName = channelName,
        expenseMinor = expenseMinor,
        incomeMinor = incomeMinor,
        coverage = coverage.name,
        confidencePercent = confidencePercent,
        warningsJson = JsonInstant.encodeToString(warnings),
    )

private fun MonthlyLedgerChannelEntity.toMonthlyLedgerChannelSummary(): MonthlyLedgerChannelSummary =
    MonthlyLedgerChannelSummary(
        channelKey = channelKey,
        channelName = channelName,
        expenseMinor = expenseMinor,
        incomeMinor = incomeMinor,
        coverage = runCatching {
            MonthlyLedgerCoverage.valueOf(coverage)
        }.getOrDefault(MonthlyLedgerCoverage.UNKNOWN),
        confidencePercent = confidencePercent,
        warnings = decodeMonthlyLedgerWarnings(warningsJson),
    )

private fun decodeMonthlyLedgerWarnings(value: String): List<String> =
    runCatching { JsonInstant.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
