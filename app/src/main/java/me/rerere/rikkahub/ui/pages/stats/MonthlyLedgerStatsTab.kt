package me.rerere.rikkahub.ui.pages.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MonthlyLedgerAggregationMode
import me.rerere.rikkahub.data.model.MonthlyLedgerChannelSummary
import me.rerere.rikkahub.data.model.MonthlyLedgerCoverage
import me.rerere.rikkahub.data.model.MonthlyLedgerSummary
import me.rerere.rikkahub.ui.theme.CustomColors
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.YearMonth
import java.util.Locale

data class MonthlyLedgerStatsUiState(
    val month: YearMonth,
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val currencies: List<MonthlyLedgerCurrencyStats> = emptyList(),
)

data class MonthlyLedgerCurrencyStats(
    val currency: String,
    val minorUnit: Int,
    val expenseMinor: Long,
    val incomeMinor: Long,
    val netMinor: Long,
    val aggregationMode: MonthlyLedgerAggregationMode,
    val coverage: MonthlyLedgerCoverage,
    val confidencePercent: Int,
    val warnings: List<String>,
    val channels: List<MonthlyLedgerChannelStats>,
)

data class MonthlyLedgerChannelStats(
    val channelKey: String,
    val channelName: String,
    val expenseMinor: Long,
    val incomeMinor: Long,
    val coverage: MonthlyLedgerCoverage,
    val confidencePercent: Int,
    val warnings: List<String>,
)

internal fun buildMonthlyLedgerStatsUiState(
    month: YearMonth,
    summaries: List<MonthlyLedgerSummary>,
): MonthlyLedgerStatsUiState = MonthlyLedgerStatsUiState(
    month = month,
    currencies = summaries
        .asSequence()
        .filter { it.month == month.toString() }
        .sortedBy { it.currency }
        .map { summary ->
            MonthlyLedgerCurrencyStats(
                currency = summary.currency,
                minorUnit = summary.minorUnit,
                expenseMinor = summary.expenseMinor,
                incomeMinor = summary.incomeMinor,
                netMinor = summary.netMinor,
                aggregationMode = summary.aggregationMode,
                coverage = summary.coverage,
                confidencePercent = summary.confidencePercent,
                warnings = summary.warnings,
                channels = summary.channels
                    .sortedWith(
                        compareByDescending<MonthlyLedgerChannelSummary> { it.expenseMinor }
                            .thenBy { it.channelName }
                            .thenBy { it.channelKey }
                    )
                    .map { channel ->
                        MonthlyLedgerChannelStats(
                            channelKey = channel.channelKey,
                            channelName = channel.channelName,
                            expenseMinor = channel.expenseMinor,
                            incomeMinor = channel.incomeMinor,
                            coverage = channel.coverage,
                            confidencePercent = channel.confidencePercent,
                            warnings = channel.warnings,
                        )
                    },
            )
        }
        .toList(),
)

internal fun formatLedgerAmount(
    currency: String,
    minorUnit: Int,
    amountMinor: Long,
    locale: Locale,
): String {
    require(minorUnit >= 0) { "minorUnit must not be negative" }
    val amount = BigDecimal.valueOf(amountMinor, minorUnit)
    val formatter = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = minorUnit
        maximumFractionDigits = minorUnit
        isGroupingUsed = true
    }
    return "$currency ${formatter.format(amount)}"
}

@Composable
internal fun MonthlyLedgerStatsTab(
    state: MonthlyLedgerStatsUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSelectNextMonth = state.month < YearMonth.now()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "month-selector") {
            LedgerMonthSelector(
                month = state.month,
                canSelectNextMonth = canSelectNextMonth,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
            )
        }

        when {
            state.isLoading -> item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.loadFailed -> item(key = "load-failed") {
                LedgerMessageCard(
                    title = stringResource(R.string.stats_page_ledger_load_failed_title),
                    message = stringResource(R.string.stats_page_ledger_load_failed_message),
                )
            }

            state.currencies.isEmpty() -> item(key = "empty") {
                LedgerMessageCard(
                    title = stringResource(R.string.stats_page_ledger_empty_title),
                    message = stringResource(R.string.stats_page_ledger_empty_message),
                )
            }

            else -> items(
                items = state.currencies,
                key = { it.currency },
            ) { currencyStats ->
                CurrencyLedgerSection(currencyStats)
            }
        }
    }
}

@Composable
private fun LedgerMonthSelector(
    month: YearMonth,
    canSelectNextMonth: Boolean,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(
                imageVector = HugeIcons.ArrowLeft01,
                contentDescription = stringResource(R.string.stats_page_ledger_previous_month),
            )
        }
        Text(
            text = stringResource(
                R.string.stats_page_ledger_month,
                month.year,
                month.monthValue,
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(
            onClick = onNextMonth,
            enabled = canSelectNextMonth,
        ) {
            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = stringResource(R.string.stats_page_ledger_next_month),
            )
        }
    }
}

@Composable
private fun LedgerMessageCard(
    title: String,
    message: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = HugeIcons.MoneyBag02,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CurrencyLedgerSection(stats: MonthlyLedgerCurrencyStats) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CurrencySummaryCard(stats)
        ChannelSummaryCard(stats)
    }
}

@Composable
private fun CurrencySummaryCard(stats: MonthlyLedgerCurrencyStats) {
    val locale = LocalLocale.current.platformLocale

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stats.currency,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                AggregationBadge(stats.aggregationMode)
            }

            LedgerAmountRow(
                label = stringResource(R.string.stats_page_ledger_expense),
                amount = formatLedgerAmount(
                    currency = stats.currency,
                    minorUnit = stats.minorUnit,
                    amountMinor = stats.expenseMinor,
                    locale = locale,
                ),
            )
            LedgerAmountRow(
                label = stringResource(R.string.stats_page_ledger_income),
                amount = formatLedgerAmount(
                    currency = stats.currency,
                    minorUnit = stats.minorUnit,
                    amountMinor = stats.incomeMinor,
                    locale = locale,
                ),
            )
            HorizontalDivider()
            LedgerAmountRow(
                label = stringResource(R.string.stats_page_ledger_net),
                amount = formatLedgerAmount(
                    currency = stats.currency,
                    minorUnit = stats.minorUnit,
                    amountMinor = stats.netMinor,
                    locale = locale,
                ),
                emphasize = true,
            )

            Text(
                text = ledgerEvidenceLabel(
                    coverage = stats.coverage,
                    confidencePercent = stats.confidencePercent,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LedgerWarnings(stats.warnings)
        }
    }
}

@Composable
private fun AggregationBadge(mode: MonthlyLedgerAggregationMode) {
    val label = when (mode) {
        MonthlyLedgerAggregationMode.RAW_CHANNEL_SUM ->
            stringResource(R.string.stats_page_ledger_aggregation_raw)

        MonthlyLedgerAggregationMode.DEDUPED_ESTIMATE ->
            stringResource(R.string.stats_page_ledger_aggregation_deduped)
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LedgerAmountRow(
    label: String,
    amount: String,
    emphasize: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = amount,
            style = if (emphasize) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ChannelSummaryCard(stats: MonthlyLedgerCurrencyStats) {
    val locale = LocalLocale.current.platformLocale

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_page_ledger_channels_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.stats_page_ledger_channels_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (stats.channels.isEmpty()) {
                Text(
                    text = stringResource(R.string.stats_page_ledger_channels_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                stats.channels.forEachIndexed { index, channel ->
                    if (index > 0) {
                        HorizontalDivider()
                    }
                    ChannelSummaryRow(
                        channel = channel,
                        currency = stats.currency,
                        minorUnit = stats.minorUnit,
                        locale = locale,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelSummaryRow(
    channel: MonthlyLedgerChannelStats,
    currency: String,
    minorUnit: Int,
    locale: Locale,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = channel.channelName,
            style = MaterialTheme.typography.titleSmall,
        )
        LedgerAmountRow(
            label = stringResource(R.string.stats_page_ledger_expense),
            amount = formatLedgerAmount(currency, minorUnit, channel.expenseMinor, locale),
        )
        LedgerAmountRow(
            label = stringResource(R.string.stats_page_ledger_income),
            amount = formatLedgerAmount(currency, minorUnit, channel.incomeMinor, locale),
        )
        Text(
            text = ledgerEvidenceLabel(
                coverage = channel.coverage,
                confidencePercent = channel.confidencePercent,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LedgerWarnings(channel.warnings)
    }
}

@Composable
private fun ledgerEvidenceLabel(
    coverage: MonthlyLedgerCoverage,
    confidencePercent: Int,
): String {
    val coverageLabel = when (coverage) {
        MonthlyLedgerCoverage.FULL -> stringResource(R.string.stats_page_ledger_coverage_full)
        MonthlyLedgerCoverage.PARTIAL -> stringResource(R.string.stats_page_ledger_coverage_partial)
        MonthlyLedgerCoverage.UNKNOWN -> stringResource(R.string.stats_page_ledger_coverage_unknown)
    }
    return stringResource(
        R.string.stats_page_ledger_evidence,
        coverageLabel,
        confidencePercent,
    )
}

@Composable
private fun LedgerWarnings(warnings: List<String>) {
    warnings.forEach { warning ->
        Text(
            text = "• $warning",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
