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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbeStage
import me.rerere.rikkahub.data.model.HealthMetricRecord
import me.rerere.rikkahub.data.model.HealthMetricSourceType
import me.rerere.rikkahub.data.model.HealthMetricType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
internal fun HealthStatsTab(
    state: HealthStatsUiState,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalNavController.current
    when {
        state.isLoading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        state.loadFailed -> HealthLoadFailed(modifier)

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("health-source") {
                HealthSourceCard(
                    state = state,
                    onOpenDevices = { navigator.navigate(Screen.SettingDevices) },
                )
            }
            if (!state.hasAnyData) {
                item("health-empty") { HealthEmptyCard() }
            }
            item("body-heading") {
                HealthSectionHeader(
                    title = stringResource(R.string.stats_page_health_body_title),
                    subtitle = stringResource(R.string.stats_page_health_body_subtitle),
                )
            }
            item("body-primary") {
                HealthMetricGrid(
                    types = BODY_PRIMARY_METRICS,
                    values = state.metrics,
                    icon = HugeIcons.ChartColumn,
                )
            }
            item("body-composition") {
                HealthMetricListCard(
                    title = stringResource(R.string.stats_page_health_composition_more),
                    types = BODY_COMPOSITION_METRICS,
                    values = state.metrics,
                )
            }
            item("vitals-heading") {
                HealthSectionHeader(
                    title = stringResource(R.string.stats_page_health_vitals_title),
                    subtitle = stringResource(R.string.stats_page_health_vitals_subtitle),
                )
            }
            item("vitals-grid") {
                HealthMetricGrid(
                    types = VITAL_METRICS,
                    values = state.metrics,
                    icon = HugeIcons.Favourite,
                )
            }
            item("activity-heading") {
                HealthSectionHeader(
                    title = stringResource(R.string.stats_page_health_activity_title),
                    subtitle = stringResource(R.string.stats_page_health_activity_subtitle),
                )
            }
            item("activity-grid") {
                HealthMetricGrid(
                    types = ACTIVITY_METRICS,
                    values = state.metrics,
                    icon = HugeIcons.Rocket01,
                )
            }
            item("sleep-heading") {
                HealthSectionHeader(
                    title = stringResource(R.string.stats_page_health_sleep_title),
                    subtitle = stringResource(R.string.stats_page_health_sleep_subtitle),
                )
            }
            item("sleep-grid") {
                HealthMetricGrid(
                    types = SLEEP_METRICS,
                    values = state.metrics,
                    icon = HugeIcons.Clock02,
                )
            }
            if (state.recentRecords.isNotEmpty()) {
                item("recent-heading") {
                    HealthSectionHeader(
                        title = stringResource(R.string.stats_page_health_recent_title),
                        subtitle = stringResource(R.string.stats_page_health_recent_subtitle),
                    )
                }
                items(state.recentRecords, key = HealthMetricRecord::id) { record ->
                    HealthRecentRecordRow(record)
                }
            }
        }
    }
}

@Composable
private fun HealthSourceCard(
    state: HealthStatsUiState,
    onOpenDevices: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(HugeIcons.Favourite, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.stats_page_health_overview_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        watchSummary(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(R.string.stats_page_health_ai_policy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenDevices) {
                Icon(HugeIcons.SmartPhone01, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.stats_page_health_manage_devices),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun watchSummary(state: HealthStatsUiState): String = when {
    state.watchStage == LenovoWatchProbeStage.SYNCING -> stringResource(
        R.string.stats_page_health_watch_syncing,
        state.watchProcessedRecords,
    )
    state.watchLastSuccessfulSyncAt != null -> stringResource(
        R.string.stats_page_health_watch_synced,
        formatHealthTime(state.watchLastSuccessfulSyncAt.toEpochMilli()),
    )
    state.watchRemembered -> stringResource(R.string.stats_page_health_watch_waiting)
    else -> stringResource(R.string.stats_page_health_watch_unavailable)
}

@Composable
private fun HealthEmptyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.stats_page_health_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.stats_page_health_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HealthLoadFailed(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.stats_page_health_load_failed),
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HealthSectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HealthMetricGrid(
    types: List<HealthMetricType>,
    values: Map<HealthMetricType, HealthMetricDisplayValue>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        types.chunked(2).forEach { rowTypes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowTypes.forEach { type ->
                    HealthMetricCard(
                        type = type,
                        value = values[type],
                        icon = icon,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowTypes.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HealthMetricCard(
    type: HealthMetricType,
    value: HealthMetricDisplayValue?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
                Text(healthMetricLabel(type), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                value?.let(::formatHealthMetricValue) ?: "--",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                healthMetricSource(value),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HealthMetricListCard(
    title: String,
    types: List<HealthMetricType>,
    values: Map<HealthMetricType, HealthMetricDisplayValue>,
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            types.forEachIndexed { index, type ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        healthMetricLabel(type),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        values[type]?.let(::formatHealthMetricValue) ?: "--",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthRecentRecordRow(record: HealthMetricRecord) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HugeIcons.Zap, contentDescription = null, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(healthMetricLabel(record.type), style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatHealthTime(record.effectiveAtEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${record.valueDecimal} ${record.unit}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun healthMetricSource(value: HealthMetricDisplayValue?): String = when {
    value == null -> stringResource(R.string.stats_page_health_no_data)
    value.calculated -> stringResource(R.string.stats_page_health_source_calculated)
    value.sourceType == HealthMetricSourceType.LENOVO_WATCH -> stringResource(
        R.string.stats_page_health_source_watch,
        value.effectiveAtEpochMillis?.let(::formatHealthTime) ?: "--",
    )
    value.sourceType == HealthMetricSourceType.AI_EXTRACTED_CHAT -> stringResource(
        R.string.stats_page_health_source_chat,
        value.effectiveAtEpochMillis?.let(::formatHealthTime) ?: "--",
    )
    else -> stringResource(R.string.stats_page_health_source_other)
}

private fun formatHealthMetricValue(value: HealthMetricDisplayValue): String =
    "${value.valueDecimal} ${value.unit}"

@Composable
private fun healthMetricLabel(type: HealthMetricType): String = stringResource(
    when (type) {
        HealthMetricType.HEIGHT_CM -> R.string.stats_page_health_metric_height
        HealthMetricType.WEIGHT_KG -> R.string.stats_page_health_metric_weight
        HealthMetricType.BODY_FAT_PERCENT -> R.string.stats_page_health_metric_body_fat
        HealthMetricType.BMI -> R.string.stats_page_health_metric_bmi
        HealthMetricType.MUSCLE_MASS_KG -> R.string.stats_page_health_metric_muscle_mass
        HealthMetricType.SKELETAL_MUSCLE_PERCENT -> R.string.stats_page_health_metric_skeletal_muscle
        HealthMetricType.BODY_WATER_PERCENT -> R.string.stats_page_health_metric_body_water
        HealthMetricType.VISCERAL_FAT_LEVEL -> R.string.stats_page_health_metric_visceral_fat
        HealthMetricType.BONE_MASS_KG -> R.string.stats_page_health_metric_bone_mass
        HealthMetricType.WAIST_CIRCUMFERENCE_CM -> R.string.stats_page_health_metric_waist
        HealthMetricType.BASAL_METABOLIC_RATE_KCAL -> R.string.stats_page_health_metric_bmr
        HealthMetricType.HEART_RATE_BPM -> R.string.stats_page_health_metric_heart_rate
        HealthMetricType.BLOOD_OXYGEN_PERCENT -> R.string.stats_page_health_metric_blood_oxygen
        HealthMetricType.SYSTOLIC_BLOOD_PRESSURE_MMHG -> R.string.stats_page_health_metric_systolic
        HealthMetricType.DIASTOLIC_BLOOD_PRESSURE_MMHG -> R.string.stats_page_health_metric_diastolic
        HealthMetricType.BODY_TEMPERATURE_CELSIUS -> R.string.stats_page_health_metric_temperature
        HealthMetricType.IMMUNITY_LEVEL -> R.string.stats_page_health_metric_immunity
        HealthMetricType.STEPS -> R.string.stats_page_health_metric_steps
        HealthMetricType.ACTIVE_CALORIES_KCAL -> R.string.stats_page_health_metric_calories
        HealthMetricType.EXERCISE_MINUTES -> R.string.stats_page_health_metric_exercise_minutes
        HealthMetricType.EXERCISE_COUNT -> R.string.stats_page_health_metric_exercise_count
        HealthMetricType.SLEEP_MINUTES -> R.string.stats_page_health_metric_sleep
        HealthMetricType.DEEP_SLEEP_MINUTES -> R.string.stats_page_health_metric_deep_sleep
        HealthMetricType.SHALLOW_SLEEP_MINUTES -> R.string.stats_page_health_metric_shallow_sleep
        HealthMetricType.AWAKE_COUNT -> R.string.stats_page_health_metric_awake_count
    },
)

private fun formatHealthTime(epochMillis: Long): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(epochMillis))

private val BODY_PRIMARY_METRICS = listOf(
    HealthMetricType.HEIGHT_CM,
    HealthMetricType.WEIGHT_KG,
    HealthMetricType.BODY_FAT_PERCENT,
    HealthMetricType.BMI,
)

private val BODY_COMPOSITION_METRICS = listOf(
    HealthMetricType.MUSCLE_MASS_KG,
    HealthMetricType.SKELETAL_MUSCLE_PERCENT,
    HealthMetricType.BODY_WATER_PERCENT,
    HealthMetricType.VISCERAL_FAT_LEVEL,
    HealthMetricType.BONE_MASS_KG,
    HealthMetricType.WAIST_CIRCUMFERENCE_CM,
    HealthMetricType.BASAL_METABOLIC_RATE_KCAL,
)

private val VITAL_METRICS = listOf(
    HealthMetricType.HEART_RATE_BPM,
    HealthMetricType.BLOOD_OXYGEN_PERCENT,
    HealthMetricType.SYSTOLIC_BLOOD_PRESSURE_MMHG,
    HealthMetricType.DIASTOLIC_BLOOD_PRESSURE_MMHG,
    HealthMetricType.BODY_TEMPERATURE_CELSIUS,
    HealthMetricType.IMMUNITY_LEVEL,
)

private val ACTIVITY_METRICS = listOf(
    HealthMetricType.STEPS,
    HealthMetricType.ACTIVE_CALORIES_KCAL,
    HealthMetricType.EXERCISE_MINUTES,
    HealthMetricType.EXERCISE_COUNT,
)

private val SLEEP_METRICS = listOf(
    HealthMetricType.SLEEP_MINUTES,
    HealthMetricType.DEEP_SLEEP_MINUTES,
    HealthMetricType.SHALLOW_SLEEP_MINUTES,
    HealthMetricType.AWAKE_COUNT,
)
