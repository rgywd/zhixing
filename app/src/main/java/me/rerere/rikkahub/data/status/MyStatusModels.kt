package me.rerere.rikkahub.data.status

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

internal const val MY_STATUS_VALIDITY_MS = 60 * 60 * 1_000L
internal const val MY_STATUS_REFRESH_DEBOUNCE_MS = 30 * 1_000L

@Serializable
internal enum class MyStatusConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
internal enum class MyStatusInsightKind(val displayName: String) {
    BODY("身体"),
    ENVIRONMENT("环境"),
    AGENDA("安排"),
}

@Serializable
internal enum class MyStatusSource {
    AI,
    LOCAL,
}

@Serializable
internal data class MyStatusEvidence(
    val id: String,
    val kind: MyStatusInsightKind,
    val label: String,
    val value: String,
    val observedAt: String? = null,
    val freshness: String,
)

@Serializable
internal data class MyStatusInsight(
    val kind: MyStatusInsightKind,
    val text: String,
    val evidenceIds: List<String>,
)

@Serializable
internal data class MyStatusRecommendation(
    val text: String,
    val evidenceIds: List<String>,
)

@Serializable
internal data class MyStatusSnapshot(
    val summary: String,
    val insights: List<MyStatusInsight>,
    val recommendation: MyStatusRecommendation?,
    val evidence: List<MyStatusEvidence>,
    val generatedAtEpochMillis: Long,
    val validUntilEpochMillis: Long,
    val confidence: MyStatusConfidence,
    val locationArea: String? = null,
    val source: MyStatusSource,
)

internal data class MyStatusUiState(
    val snapshot: MyStatusSnapshot? = null,
    val refreshing: Boolean = false,
    val locationPermissionRequired: Boolean = false,
    val statusMessage: String? = null,
)

@Serializable
internal data class MyStatusLocationContext(
    val area: String,
    val scene: String? = null,
    val observedAt: String,
    val confidence: MyStatusConfidence,
)

@Serializable
internal data class MyStatusWeatherFacts(
    val condition: String,
    val temperatureCelsius: Double,
    val apparentTemperatureCelsius: Double,
    val relativeHumidityPercent: Int,
    val precipitationMillimeters: Double,
    val windSpeedKmh: Double,
    val observedAt: String,
)

@Serializable
internal data class MyStatusBodyFacts(
    val sleepMinutes: Int? = null,
    val deepSleepMinutes: Int? = null,
    val heartRateBpm: Int? = null,
    val bloodOxygenPercent: Int? = null,
    val steps: Int? = null,
    val caloriesKcal: Int? = null,
    val exerciseCount: Int? = null,
    val observedAt: String? = null,
) {
    fun hasData(): Boolean = listOf(
        sleepMinutes,
        heartRateBpm,
        bloodOxygenPercent,
        steps,
        caloriesKcal,
        exerciseCount,
    ).any { it != null }
}

@Serializable
internal data class MyStatusAgendaFacts(
    val pendingCount: Int,
    val overdueCount: Int,
    val nextItems: List<MyStatusAgendaItem>,
    val observedAt: String,
)

@Serializable
internal data class MyStatusAgendaItem(
    val title: String,
    val dueAt: String? = null,
)

@Serializable
internal data class MyStatusModelInput(
    val observedAt: String,
    val timePeriod: String,
    val location: MyStatusLocationContext? = null,
    val weather: MyStatusWeatherFacts? = null,
    val body: MyStatusBodyFacts? = null,
    val agenda: MyStatusAgendaFacts? = null,
    val evidence: List<MyStatusEvidence> = emptyList(),
)

internal data class CollectedMyStatusFacts(
    val observedAtEpochMillis: Long,
    val location: MyStatusLocationContext? = null,
    val weather: MyStatusWeatherFacts? = null,
    val body: MyStatusBodyFacts? = null,
    val agenda: MyStatusAgendaFacts? = null,
    val evidence: List<MyStatusEvidence> = emptyList(),
    val weatherUnavailable: Boolean = false,
)

private val modelInputJson = Json {
    encodeDefaults = false
    explicitNulls = false
}

internal fun buildMyStatusModelInput(
    facts: CollectedMyStatusFacts,
    allowBodyInAiContext: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
): MyStatusModelInput {
    val body = facts.body.takeIf { allowBodyInAiContext && it?.hasData() == true }
    val modelEvidence = facts.evidence.filterNot { evidence ->
        !allowBodyInAiContext && evidence.kind == MyStatusInsightKind.BODY
    }
    return MyStatusModelInput(
        observedAt = formatInstant(facts.observedAtEpochMillis),
        timePeriod = timePeriod(
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(facts.observedAtEpochMillis), zoneId).hour
        ),
        location = facts.location,
        weather = facts.weather,
        body = body,
        agenda = facts.agenda,
        evidence = modelEvidence,
    )
}

internal fun encodeMyStatusModelInput(input: MyStatusModelInput): String =
    modelInputJson.encodeToString(input)

/**
 * Only semantically meaningful buckets participate in the fingerprint. Sensor jitter and tiny
 * weather changes therefore do not keep replacing an otherwise stable conclusion.
 */
internal fun significantStatusFingerprint(
    facts: CollectedMyStatusFacts,
    allowBodyInAiContext: Boolean,
): String = buildString {
    append(facts.observedAtEpochMillis / MY_STATUS_VALIDITY_MS)
    append('|')
    append(allowBodyInAiContext)
    append('|')
    append(facts.location?.area.orEmpty())
    append('|')
    facts.weather?.let { weather ->
        append(weather.condition)
        append(':')
        append(floor(weather.apparentTemperatureCelsius / 2.0).toInt())
        append(':')
        append(weather.relativeHumidityPercent / 10)
        append(':')
        append(weather.precipitationMillimeters >= 0.2)
        append(':')
        append(floor(weather.windSpeedKmh / 10.0).toInt())
    }
    append('|')
    facts.body?.let { body ->
        append(
            listOf(
                body.sleepMinutes,
                body.deepSleepMinutes,
                body.heartRateBpm,
                body.bloodOxygenPercent,
                body.steps,
                body.caloriesKcal,
                body.exerciseCount,
            ).joinToString(",")
        )
    }
    append('|')
    facts.agenda?.let { agenda ->
        append("${agenda.pendingCount}:${agenda.overdueCount}:")
        append(agenda.nextItems.joinToString(";") { "${it.title}:${it.dueAt.orEmpty()}" })
    }
}

internal fun shouldRefreshMyStatus(
    previousFingerprint: String?,
    currentFingerprint: String,
    lastAttemptAtEpochMillis: Long?,
    lastSuccessfulAtEpochMillis: Long?,
    nowEpochMillis: Long,
    force: Boolean = false,
): Boolean {
    if (
        lastAttemptAtEpochMillis != null &&
        nowEpochMillis - lastAttemptAtEpochMillis < MY_STATUS_REFRESH_DEBOUNCE_MS
    ) {
        return false
    }
    if (force || previousFingerprint == null || previousFingerprint != currentFingerprint) return true
    return lastSuccessfulAtEpochMillis == null ||
        nowEpochMillis - lastSuccessfulAtEpochMillis >= MY_STATUS_VALIDITY_MS
}

internal fun MyStatusSnapshot.semanticContentKey(): String = buildString {
    append(summary.normalizedStatusText())
    append('|')
    append(insights.joinToString(";") { "${it.kind}:${it.text.normalizedStatusText()}" })
    append('|')
    append(recommendation?.text?.normalizedStatusText().orEmpty())
}

internal fun stabilizeMyStatusSnapshot(
    previous: MyStatusSnapshot?,
    next: MyStatusSnapshot,
): MyStatusSnapshot {
    if (previous == null || previous.semanticContentKey() != next.semanticContentKey()) return next
    return next.copy(
        summary = previous.summary,
        insights = previous.insights,
        recommendation = previous.recommendation,
    )
}

@Serializable
private data class GeneratedStatusResponse(
    val summary: String,
    val summaryEvidenceIds: List<String> = emptyList(),
    val insights: List<GeneratedStatusInsight> = emptyList(),
    val recommendation: GeneratedStatusRecommendation? = null,
    val confidence: String = "medium",
)

@Serializable
private data class GeneratedStatusInsight(
    val category: String,
    val text: String,
    val evidenceIds: List<String> = emptyList(),
)

@Serializable
private data class GeneratedStatusRecommendation(
    val text: String,
    val evidenceIds: List<String> = emptyList(),
)

internal fun parseGeneratedMyStatus(
    raw: String,
    availableEvidence: List<MyStatusEvidence>,
    nowEpochMillis: Long,
    locationArea: String?,
): MyStatusSnapshot? = runCatching {
    val response = modelInputJson.decodeFromString<GeneratedStatusResponse>(extractJsonObject(raw))
    val evidenceIds = availableEvidence.mapTo(hashSetOf(), MyStatusEvidence::id)
    val summary = response.summary.validatedStatusText(maxLength = 120)
    validateEvidenceIds(response.summaryEvidenceIds, evidenceIds, availableEvidence.isNotEmpty())

    val insights = response.insights.take(2).map { insight ->
        val kind = runCatching {
            MyStatusInsightKind.valueOf(insight.category.trim().uppercase(Locale.ROOT))
        }.getOrElse { error("Unsupported status category") }
        validateEvidenceIds(insight.evidenceIds, evidenceIds, required = true)
        MyStatusInsight(
            kind = kind,
            text = insight.text.validatedStatusText(maxLength = 140),
            evidenceIds = insight.evidenceIds.distinct(),
        )
    }
    val recommendation = response.recommendation?.let { item ->
        validateEvidenceIds(item.evidenceIds, evidenceIds, required = true)
        MyStatusRecommendation(
            text = item.text.validatedStatusText(maxLength = 120),
            evidenceIds = item.evidenceIds.distinct(),
        )
    }
    MyStatusSnapshot(
        summary = summary,
        insights = insights,
        recommendation = recommendation,
        evidence = availableEvidence,
        generatedAtEpochMillis = nowEpochMillis,
        validUntilEpochMillis = nowEpochMillis + MY_STATUS_VALIDITY_MS,
        confidence = when (response.confidence.trim().lowercase(Locale.ROOT)) {
            "high" -> MyStatusConfidence.HIGH
            "low" -> MyStatusConfidence.LOW
            else -> MyStatusConfidence.MEDIUM
        },
        locationArea = locationArea,
        source = MyStatusSource.AI,
    )
}.getOrNull()

internal fun buildLocalMyStatusFallback(
    facts: CollectedMyStatusFacts,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): MyStatusSnapshot {
    data class Candidate(
        val priority: Int,
        val kind: MyStatusInsightKind,
        val text: String,
        val evidenceIds: List<String>,
        val summary: String? = null,
        val recommendation: String? = null,
    )

    val candidates = mutableListOf<Candidate>()
    val agenda = facts.agenda
    if (agenda != null && agenda.overdueCount > 0) {
        candidates += Candidate(
            priority = 100,
            kind = MyStatusInsightKind.AGENDA,
            text = "有 ${agenda.overdueCount} 项安排已经逾期。",
            evidenceIds = listOf("agenda.overdue"),
            summary = "当前最值得注意的是逾期事项，先收口一件会更轻松。",
            recommendation = "先处理最短的一项逾期安排。",
        )
    }

    val body = facts.body
    if (body?.sleepMinutes != null && body.sleepMinutes < 360) {
        candidates += Candidate(
            priority = 90,
            kind = MyStatusInsightKind.BODY,
            text = "昨晚记录到的睡眠不足 6 小时，恢复可能偏弱。",
            evidenceIds = listOf("body.sleep"),
            summary = "今天的恢复可能偏弱，适合把节奏放稳一些。",
            recommendation = "把下一段高强度安排缩短一些，并留出休息间隔。",
        )
    }
    if (body?.bloodOxygenPercent != null && body.bloodOxygenPercent < 95) {
        candidates += Candidate(
            priority = 85,
            kind = MyStatusInsightKind.BODY,
            text = "最近一次血氧读数偏低，单次读数可能受佩戴状态影响。",
            evidenceIds = listOf("body.bloodOxygen"),
            summary = "有一项身体读数值得复核，但单次数据不足以下结论。",
            recommendation = "调整佩戴位置，安静休息后再测一次。",
        )
    }

    val weather = facts.weather
    if (weather != null && weather.apparentTemperatureCelsius >= 32.0) {
        candidates += Candidate(
            priority = 80,
            kind = MyStatusInsightKind.ENVIRONMENT,
            text = "当前体感温度较高，长时间户外活动会更费力。",
            evidenceIds = listOf("weather.apparentTemperature"),
            summary = "外部环境偏热，今天更适合减少高温时段的户外消耗。",
            recommendation = "把户外活动移到更凉快的时段，并及时补水。",
        )
    } else if (weather != null && weather.precipitationMillimeters >= 0.2) {
        candidates += Candidate(
            priority = 75,
            kind = MyStatusInsightKind.ENVIRONMENT,
            text = "当前有降水，出行时间可能需要多留一点余量。",
            evidenceIds = listOf("weather.precipitation"),
            summary = "天气会影响接下来的出行，行程最好留一点缓冲。",
            recommendation = "出门前确认雨具和路况。",
        )
    }

    val hour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowEpochMillis), zoneId).hour
    if (body?.steps != null && hour >= 18 && body.steps < 3_000) {
        candidates += Candidate(
            priority = 60,
            kind = MyStatusInsightKind.BODY,
            text = "今天到目前为止活动量不多。",
            evidenceIds = listOf("body.steps"),
            recommendation = "如果状态允许，晚些时候轻松走动十分钟。",
        )
    }

    if (agenda != null && agenda.pendingCount > 0 && agenda.overdueCount == 0) {
        candidates += Candidate(
            priority = 50,
            kind = MyStatusInsightKind.AGENDA,
            text = "目前有 ${agenda.pendingCount} 项待处理安排。",
            evidenceIds = listOf("agenda.pending"),
        )
    }
    if (weather != null && candidates.none { it.kind == MyStatusInsightKind.ENVIRONMENT }) {
        candidates += Candidate(
            priority = 30,
            kind = MyStatusInsightKind.ENVIRONMENT,
            text = "当前${weather.condition}，体感 ${weather.apparentTemperatureCelsius.formatOneDecimal()}℃。",
            evidenceIds = listOf("weather.condition", "weather.apparentTemperature"),
        )
    }
    if (body?.sleepMinutes != null && candidates.none { it.kind == MyStatusInsightKind.BODY }) {
        candidates += Candidate(
            priority = 25,
            kind = MyStatusInsightKind.BODY,
            text = "昨晚记录到 ${formatMinutes(body.sleepMinutes)} 睡眠。",
            evidenceIds = listOf("body.sleep"),
        )
    }

    val selected = candidates.sortedByDescending(Candidate::priority).take(2)
    val primary = selected.firstOrNull()
    return MyStatusSnapshot(
        summary = primary?.summary
            ?: if (facts.evidence.isEmpty()) {
                "目前可用信息还不够，先按原计划推进即可。"
            } else {
                "目前没有明显需要调整的信号，可以按原计划推进。"
            },
        insights = selected.map {
            MyStatusInsight(it.kind, it.text, it.evidenceIds)
        },
        recommendation = primary?.recommendation?.let {
            MyStatusRecommendation(it, primary.evidenceIds)
        },
        evidence = facts.evidence,
        generatedAtEpochMillis = nowEpochMillis,
        validUntilEpochMillis = nowEpochMillis + MY_STATUS_VALIDITY_MS,
        confidence = if (facts.evidence.isEmpty()) MyStatusConfidence.LOW else MyStatusConfidence.MEDIUM,
        locationArea = facts.location?.area,
        source = MyStatusSource.LOCAL,
    )
}

internal fun timePeriod(hour: Int): String = when (hour) {
    in 0..5 -> "深夜"
    in 6..10 -> "上午"
    in 11..13 -> "中午"
    in 14..17 -> "下午"
    else -> "晚上"
}

internal fun formatInstant(epochMillis: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

private fun validateEvidenceIds(ids: List<String>, available: Set<String>, required: Boolean) {
    require(!required || ids.isNotEmpty()) { "Status text must cite supplied evidence" }
    require(ids.all { it in available }) { "Status response cites unavailable evidence" }
}

private fun extractJsonObject(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    require(start >= 0 && end > start) { "Status response does not contain JSON" }
    return trimmed.substring(start, end + 1)
}

private fun String.validatedStatusText(maxLength: Int): String {
    val value = normalizedStatusText()
    require(value.isNotBlank() && value.length <= maxLength) { "Invalid status text length" }
    require(FORBIDDEN_MEDICAL_TERMS.none { value.contains(it, ignoreCase = true) }) {
        "Medical diagnosis is not allowed"
    }
    return value
}

private fun String.normalizedStatusText(): String = trim().replace(Regex("\\s+"), " ")

private fun Double.formatOneDecimal(): String = String.format(Locale.CHINA, "%.1f", this)

private fun formatMinutes(value: Int): String = when {
    value < 60 -> "${value}分钟"
    value % 60 == 0 -> "${value / 60}小时"
    else -> "${value / 60}小时${value % 60}分"
}

private val FORBIDDEN_MEDICAL_TERMS = listOf(
    "诊断",
    "确诊",
    "患有",
    "疾病",
    "治疗",
    "用药",
    "diagnos",
)
