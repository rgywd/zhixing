package me.rerere.rikkahub.ui.pages.stats

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchHealthSnapshot
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbeStage
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbeState
import me.rerere.rikkahub.data.model.HealthMetricRecord
import me.rerere.rikkahub.data.model.HealthMetricSourceType
import me.rerere.rikkahub.data.model.HealthMetricType

internal data class HealthMetricDisplayValue(
    val type: HealthMetricType,
    val valueDecimal: String,
    val unit: String,
    val effectiveAtEpochMillis: Long?,
    val sourceType: HealthMetricSourceType,
    val calculated: Boolean = false,
)

internal data class HealthStatsUiState(
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
    val metrics: Map<HealthMetricType, HealthMetricDisplayValue> = emptyMap(),
    val history: Map<HealthMetricType, List<HealthMetricRecord>> = emptyMap(),
    val recentRecords: List<HealthMetricRecord> = emptyList(),
    val watchRemembered: Boolean = false,
    val watchStage: LenovoWatchProbeStage = LenovoWatchProbeStage.IDLE,
    val watchProcessedRecords: Int = 0,
    val watchLastSuccessfulSyncAt: Instant? = null,
) {
    val hasAnyData: Boolean get() = metrics.isNotEmpty()
}

internal fun buildHealthStatsUiState(
    records: List<HealthMetricRecord>,
    watchState: LenovoWatchProbeState,
): HealthStatsUiState {
    val sortedRecords = records.sortedWith(
        compareByDescending<HealthMetricRecord> { it.effectiveAtEpochMillis }
            .thenByDescending { it.recordedAtEpochMillis }
            .thenByDescending { it.id },
    )
    val history = sortedRecords.groupBy(HealthMetricRecord::type)
    val metrics = history.mapValues { (type, values) -> values.first().toDisplayValue(type) }.toMutableMap()
    mergeWatchMetrics(
        target = metrics,
        snapshot = watchState.health,
        syncAtEpochMillis = watchState.lastSuccessfulSyncAt?.toEpochMilli(),
    )
    if (metrics[HealthMetricType.BMI] == null) {
        calculateBmi(metrics)?.let { metrics[HealthMetricType.BMI] = it }
    }
    return HealthStatsUiState(
        metrics = metrics,
        history = history,
        recentRecords = sortedRecords.take(RECENT_RECORD_LIMIT),
        watchRemembered = watchState.remembered,
        watchStage = watchState.stage,
        watchProcessedRecords = watchState.processedRecords,
        watchLastSuccessfulSyncAt = watchState.lastSuccessfulSyncAt,
    )
}

private fun HealthMetricRecord.toDisplayValue(type: HealthMetricType = this.type) = HealthMetricDisplayValue(
    type = type,
    valueDecimal = valueDecimal,
    unit = unit,
    effectiveAtEpochMillis = effectiveAtEpochMillis,
    sourceType = sourceType,
)

private fun mergeWatchMetrics(
    target: MutableMap<HealthMetricType, HealthMetricDisplayValue>,
    snapshot: LenovoWatchHealthSnapshot,
    syncAtEpochMillis: Long?,
) {
    val watchValues = buildMap {
        snapshot.heartRate?.let { put(HealthMetricType.HEART_RATE_BPM, it.toString()) }
        snapshot.bloodOxygen?.let { put(HealthMetricType.BLOOD_OXYGEN_PERCENT, it.toString()) }
        snapshot.systolic?.let { put(HealthMetricType.SYSTOLIC_BLOOD_PRESSURE_MMHG, it.toString()) }
        snapshot.diastolic?.let { put(HealthMetricType.DIASTOLIC_BLOOD_PRESSURE_MMHG, it.toString()) }
        snapshot.temperatureCelsius?.let {
            put(HealthMetricType.BODY_TEMPERATURE_CELSIUS, it.toHealthDecimal())
        }
        snapshot.immunity?.let { put(HealthMetricType.IMMUNITY_LEVEL, it.toString()) }
        snapshot.steps?.let { put(HealthMetricType.STEPS, it.toString()) }
        snapshot.calories?.let { put(HealthMetricType.ACTIVE_CALORIES_KCAL, it.toString()) }
        snapshot.exerciseSeconds?.let {
            put(
                HealthMetricType.EXERCISE_MINUTES,
                BigDecimal(it).divide(BigDecimal(60), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
            )
        }
        snapshot.exerciseCount?.let { put(HealthMetricType.EXERCISE_COUNT, it.toString()) }
        snapshot.totalSleepMinutes?.let { put(HealthMetricType.SLEEP_MINUTES, it.toString()) }
        snapshot.deepSleepMinutes?.let { put(HealthMetricType.DEEP_SLEEP_MINUTES, it.toString()) }
        snapshot.shallowSleepMinutes?.let { put(HealthMetricType.SHALLOW_SLEEP_MINUTES, it.toString()) }
        snapshot.awakeCount?.let { put(HealthMetricType.AWAKE_COUNT, it.toString()) }
    }
    watchValues.forEach { (type, value) ->
        val existing = target[type]
        if (existing == null || syncAtEpochMillis != null && existing.effectiveAtEpochMillis.orZero() <= syncAtEpochMillis) {
            target[type] = HealthMetricDisplayValue(
                type = type,
                valueDecimal = value,
                unit = type.canonicalUnit,
                effectiveAtEpochMillis = syncAtEpochMillis,
                sourceType = HealthMetricSourceType.LENOVO_WATCH,
            )
        }
    }
}

private fun calculateBmi(
    metrics: Map<HealthMetricType, HealthMetricDisplayValue>,
): HealthMetricDisplayValue? {
    val heightCm = metrics[HealthMetricType.HEIGHT_CM]?.valueDecimal?.toBigDecimalOrNull() ?: return null
    val weightKg = metrics[HealthMetricType.WEIGHT_KG]?.valueDecimal?.toBigDecimalOrNull() ?: return null
    if (heightCm <= BigDecimal.ZERO || weightKg <= BigDecimal.ZERO) return null
    val heightMeters = heightCm.movePointLeft(2)
    val bmi = weightKg.divide(heightMeters.multiply(heightMeters), 1, RoundingMode.HALF_UP)
    return HealthMetricDisplayValue(
        type = HealthMetricType.BMI,
        valueDecimal = bmi.stripTrailingZeros().toPlainString(),
        unit = HealthMetricType.BMI.canonicalUnit,
        effectiveAtEpochMillis = maxOf(
            metrics.getValue(HealthMetricType.HEIGHT_CM).effectiveAtEpochMillis.orZero(),
            metrics.getValue(HealthMetricType.WEIGHT_KG).effectiveAtEpochMillis.orZero(),
        ).takeIf { it > 0 },
        sourceType = HealthMetricSourceType.CALCULATED,
        calculated = true,
    )
}

private fun Double.toHealthDecimal(): String =
    BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()

private fun Long?.orZero(): Long = this ?: 0L

private const val RECENT_RECORD_LIMIT = 12
