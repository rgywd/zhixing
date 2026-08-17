package me.rerere.rikkahub.data.model

import java.math.BigDecimal

enum class HealthMetricType(
    val canonicalUnit: String,
    private val minimum: String,
    private val maximum: String,
) {
    HEIGHT_CM("cm", "20", "300"),
    WEIGHT_KG("kg", "0.5", "1000"),
    BODY_FAT_PERCENT("%", "0", "100"),
    BMI("kg/m²", "5", "100"),
    MUSCLE_MASS_KG("kg", "0", "500"),
    SKELETAL_MUSCLE_PERCENT("%", "0", "100"),
    BODY_WATER_PERCENT("%", "0", "100"),
    VISCERAL_FAT_LEVEL("level", "0", "100"),
    BONE_MASS_KG("kg", "0", "50"),
    WAIST_CIRCUMFERENCE_CM("cm", "10", "500"),
    BASAL_METABOLIC_RATE_KCAL("kcal/day", "100", "20000"),
    HEART_RATE_BPM("bpm", "1", "300"),
    BLOOD_OXYGEN_PERCENT("%", "0", "100"),
    SYSTOLIC_BLOOD_PRESSURE_MMHG("mmHg", "1", "350"),
    DIASTOLIC_BLOOD_PRESSURE_MMHG("mmHg", "1", "250"),
    BODY_TEMPERATURE_CELSIUS("°C", "20", "50"),
    IMMUNITY_LEVEL("level", "0", "100"),
    STEPS("steps", "0", "500000"),
    ACTIVE_CALORIES_KCAL("kcal", "0", "50000"),
    EXERCISE_MINUTES("min", "0", "1440"),
    EXERCISE_COUNT("times", "0", "1000"),
    SLEEP_MINUTES("min", "0", "1440"),
    DEEP_SLEEP_MINUTES("min", "0", "1440"),
    SHALLOW_SLEEP_MINUTES("min", "0", "1440"),
    AWAKE_COUNT("times", "0", "1000"),
    ;

    fun normalizeValue(raw: String): String {
        require(DECIMAL_VALUE.matches(raw)) { "value must be a non-negative decimal string" }
        val value = runCatching { BigDecimal(raw) }
            .getOrElse { throw IllegalArgumentException("value is not a supported decimal") }
        require(value.scale().coerceAtLeast(0) <= MAX_DECIMAL_SCALE) {
            "value supports at most $MAX_DECIMAL_SCALE decimal places"
        }
        require(value >= BigDecimal(minimum) && value <= BigDecimal(maximum)) {
            "value is outside the supported range for $name"
        }
        return value.stripTrailingZeros().toPlainString()
    }

    private companion object {
        const val MAX_DECIMAL_SCALE = 3
        val DECIMAL_VALUE = Regex("""^(?:0|[1-9]\d*)(?:\.\d+)?$""")
    }
}

enum class HealthMetricSourceType {
    AI_EXTRACTED_CHAT,
    USER_ENTRY,
    LENOVO_WATCH,
    EXTERNAL_SOURCE,
    CALCULATED,
}

data class HealthMetricRecord(
    val id: String,
    val type: HealthMetricType,
    val valueDecimal: String,
    val unit: String,
    val observedAtEpochMillis: Long?,
    val recordedAtEpochMillis: Long,
    val sourceType: HealthMetricSourceType,
    val sourceConversationId: String? = null,
    val sourceMessageId: String? = null,
) {
    val effectiveAtEpochMillis: Long get() = observedAtEpochMillis ?: recordedAtEpochMillis
}

data class HealthMetricDraft(
    val type: HealthMetricType,
    val valueDecimal: String,
    val observedAtEpochMillis: Long? = null,
)

data class HealthMetricChatSource(
    val conversationId: String,
    val messageId: String,
    val recordedAtEpochMillis: Long,
)
