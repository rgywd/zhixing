package me.rerere.rikkahub.data.status

import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.round

internal fun interface MyStatusClock {
    fun nowEpochMillis(): Long
}

internal interface MyStatusLocationProvider {
    fun hasPermission(): Boolean

    suspend fun current(): LocalStatusLocation?
}

internal interface WeatherProvider {
    suspend fun current(point: WeatherQueryPoint): WeatherObservation
}

internal fun interface MyStatusBodySource {
    suspend fun current(): MyStatusBodyFacts?
}

internal fun interface MyStatusAgendaSource {
    suspend fun current(): MyStatusAgendaFacts?
}

/**
 * Coordinates live only in this local transport object. The class deliberately has no serializer
 * and redacts [toString] so it cannot accidentally become model context or useful log output.
 */
internal class WeatherQueryPoint private constructor(
    internal val requestLatitude: Double,
    internal val requestLongitude: Double,
) {
    override fun toString(): String = "WeatherQueryPoint(redacted)"

    override fun equals(other: Any?): Boolean =
        other is WeatherQueryPoint &&
            requestLatitude == other.requestLatitude &&
            requestLongitude == other.requestLongitude

    override fun hashCode(): Int = 31 * requestLatitude.hashCode() + requestLongitude.hashCode()

    companion object {
        fun fromRaw(latitude: Double, longitude: Double): WeatherQueryPoint {
            require(latitude in -90.0..90.0)
            require(longitude in -180.0..180.0)
            return WeatherQueryPoint(
                requestLatitude = coarseCoordinate(latitude),
                requestLongitude = coarseCoordinate(longitude),
            )
        }
    }
}

internal data class LocalStatusLocation(
    val modelContext: MyStatusLocationContext,
    val weatherQueryPoint: WeatherQueryPoint,
)

internal data class WeatherObservation(
    val condition: String,
    val temperatureCelsius: Double,
    val apparentTemperatureCelsius: Double,
    val relativeHumidityPercent: Int,
    val precipitationMillimeters: Double,
    val windSpeedKmh: Double,
    val observedAt: String,
) {
    fun toModelFacts(): MyStatusWeatherFacts = MyStatusWeatherFacts(
        condition = condition,
        temperatureCelsius = temperatureCelsius,
        apparentTemperatureCelsius = apparentTemperatureCelsius,
        relativeHumidityPercent = relativeHumidityPercent,
        precipitationMillimeters = precipitationMillimeters,
        windSpeedKmh = windSpeedKmh,
        observedAt = observedAt,
    )
}

internal data class GeocodedArea(
    val countryName: String? = null,
    val adminArea: String? = null,
    val subAdminArea: String? = null,
    val locality: String? = null,
)

/**
 * Only administrative-area fields are accepted. Street, sub-locality, feature name and POI fields
 * are intentionally absent from the type.
 */
internal fun buildAreaLabel(area: GeocodedArea): String? {
    val parts = listOf(area.adminArea, area.locality, area.subAdminArea)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
    return parts.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
        ?: area.countryName?.trim()?.takeIf(String::isNotEmpty)
}

internal class MyStatusContextAssembler(
    private val locationProvider: MyStatusLocationProvider,
    private val weatherProvider: WeatherProvider,
    private val bodySource: MyStatusBodySource,
    private val agendaSource: MyStatusAgendaSource,
    private val clock: MyStatusClock = MyStatusClock(System::currentTimeMillis),
) {
    suspend fun collect(): CollectedMyStatusFacts {
        val now = clock.nowEpochMillis()
        val location = runCatching { locationProvider.current() }.getOrNull()
        val weatherResult = location?.let { local ->
            runCatching { weatherProvider.current(local.weatherQueryPoint) }
        }
        val weather = weatherResult?.getOrNull()?.toModelFacts()
        val body = runCatching { bodySource.current() }.getOrNull()
        val agenda = runCatching { agendaSource.current() }.getOrNull()
        val locationContext = location?.modelContext

        return CollectedMyStatusFacts(
            observedAtEpochMillis = now,
            location = locationContext,
            weather = weather,
            body = body,
            agenda = agenda,
            evidence = buildEvidence(
                nowEpochMillis = now,
                location = locationContext,
                weather = weather,
                body = body,
                agenda = agenda,
            ),
            weatherUnavailable = weatherResult?.isFailure == true,
        )
    }

    fun locationPermissionRequired(): Boolean = !locationProvider.hasPermission()
}

private fun buildEvidence(
    nowEpochMillis: Long,
    location: MyStatusLocationContext?,
    weather: MyStatusWeatherFacts?,
    body: MyStatusBodyFacts?,
    agenda: MyStatusAgendaFacts?,
): List<MyStatusEvidence> = buildList {
    location?.let {
        add(
            MyStatusEvidence(
                id = "environment.location",
                kind = MyStatusInsightKind.ENVIRONMENT,
                label = "位置",
                value = it.scene?.let { scene -> "${it.area} · $scene" } ?: it.area,
                observedAt = it.observedAt,
                freshness = freshness(it.observedAt, nowEpochMillis),
            )
        )
    }
    weather?.let {
        add(
            MyStatusEvidence(
                id = "weather.condition",
                kind = MyStatusInsightKind.ENVIRONMENT,
                label = "天气 · Open-Meteo",
                value = it.condition,
                observedAt = it.observedAt,
                freshness = freshness(it.observedAt, nowEpochMillis),
            )
        )
        add(
            MyStatusEvidence(
                id = "weather.apparentTemperature",
                kind = MyStatusInsightKind.ENVIRONMENT,
                label = "体感温度",
                value = "${it.apparentTemperatureCelsius.oneDecimal()}℃",
                observedAt = it.observedAt,
                freshness = freshness(it.observedAt, nowEpochMillis),
            )
        )
        add(
            MyStatusEvidence(
                id = "weather.precipitation",
                kind = MyStatusInsightKind.ENVIRONMENT,
                label = "降水",
                value = "${it.precipitationMillimeters.oneDecimal()} mm",
                observedAt = it.observedAt,
                freshness = freshness(it.observedAt, nowEpochMillis),
            )
        )
        add(
            MyStatusEvidence(
                id = "weather.humidity",
                kind = MyStatusInsightKind.ENVIRONMENT,
                label = "湿度",
                value = "${it.relativeHumidityPercent}%",
                observedAt = it.observedAt,
                freshness = freshness(it.observedAt, nowEpochMillis),
            )
        )
    }
    body?.let {
        it.sleepMinutes?.let { value ->
            add(bodyEvidence("body.sleep", "睡眠", formatMinutes(value), it.observedAt, nowEpochMillis))
        }
        it.heartRateBpm?.let { value ->
            add(bodyEvidence("body.heartRate", "心率", "$value bpm", it.observedAt, nowEpochMillis))
        }
        it.bloodOxygenPercent?.let { value ->
            add(bodyEvidence("body.bloodOxygen", "血氧", "$value%", it.observedAt, nowEpochMillis))
        }
        it.steps?.let { value ->
            add(bodyEvidence("body.steps", "步数", String.format(Locale.CHINA, "%,d 步", value), it.observedAt, nowEpochMillis))
        }
        it.caloriesKcal?.let { value ->
            add(bodyEvidence("body.calories", "卡路里", "$value 千卡", it.observedAt, nowEpochMillis))
        }
        it.exerciseCount?.let { value ->
            add(bodyEvidence("body.exerciseCount", "运动次数", "$value 次", it.observedAt, nowEpochMillis))
        }
    }
    agenda?.let {
        add(
            MyStatusEvidence(
                id = "agenda.pending",
                kind = MyStatusInsightKind.AGENDA,
                label = "待处理",
                value = "${it.pendingCount} 项",
                observedAt = it.observedAt,
                freshness = freshness(it.observedAt, nowEpochMillis),
            )
        )
        if (it.overdueCount > 0) {
            add(
                MyStatusEvidence(
                    id = "agenda.overdue",
                    kind = MyStatusInsightKind.AGENDA,
                    label = "已逾期",
                    value = "${it.overdueCount} 项",
                    observedAt = it.observedAt,
                    freshness = freshness(it.observedAt, nowEpochMillis),
                )
            )
        }
    }
}

private fun bodyEvidence(
    id: String,
    label: String,
    value: String,
    observedAt: String?,
    nowEpochMillis: Long,
): MyStatusEvidence = MyStatusEvidence(
    id = id,
    kind = MyStatusInsightKind.BODY,
    label = label,
    value = value,
    observedAt = observedAt,
    freshness = freshness(observedAt, nowEpochMillis),
)

private fun freshness(observedAt: String?, nowEpochMillis: Long): String {
    val instant = observedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "采集时间未知"
    val minutes = Duration.between(instant, Instant.ofEpochMilli(nowEpochMillis)).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "刚刚更新"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> "${minutes / (24 * 60)} 天前"
    }
}

private fun coarseCoordinate(value: Double): Double = round(value * 10.0) / 10.0

private fun Double.oneDecimal(): String = String.format(Locale.CHINA, "%.1f", this)

private fun formatMinutes(value: Int): String = when {
    value < 60 -> "$value 分钟"
    value % 60 == 0 -> "${value / 60} 小时"
    else -> "${value / 60}小时${value % 60}分"
}
