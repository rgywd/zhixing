package me.rerere.rikkahub.data.status

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Open-Meteo's public forecast endpoint does not require an API key. This provider owns a dedicated
 * client without logging interceptors because even coarse query coordinates should not enter logs.
 */
internal class OpenMeteoWeatherProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val clock: MyStatusClock = MyStatusClock(System::currentTimeMillis),
) : WeatherProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun current(point: WeatherQueryPoint): WeatherObservation = withContext(Dispatchers.IO) {
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", point.requestLatitude.requestCoordinate())
            .addQueryParameter("longitude", point.requestLongitude.requestCoordinate())
            .addQueryParameter(
                "current",
                "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m",
            )
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Weather service returned HTTP ${response.code}" }
            val body = response.body.string()
            val current = json.decodeFromString<OpenMeteoResponse>(body).current
                ?: error("Weather response has no current conditions")
            WeatherObservation(
                condition = weatherCodeLabel(current.weatherCode),
                temperatureCelsius = current.temperatureCelsius,
                apparentTemperatureCelsius = current.apparentTemperatureCelsius,
                relativeHumidityPercent = current.relativeHumidityPercent,
                precipitationMillimeters = current.precipitationMillimeters,
                windSpeedKmh = current.windSpeedKmh,
                observedAt = formatInstant(clock.nowEpochMillis()),
            )
        }
    }

    private companion object {
        const val DEFAULT_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
    }
}

internal class CachingWeatherProvider(
    private val delegate: WeatherProvider,
    private val clock: MyStatusClock = MyStatusClock(System::currentTimeMillis),
) : WeatherProvider {
    private val mutex = Mutex()
    private var cached: CacheEntry? = null

    override suspend fun current(point: WeatherQueryPoint): WeatherObservation = mutex.withLock {
        val now = clock.nowEpochMillis()
        cached?.takeIf { it.point == point && now - it.fetchedAtEpochMillis < CACHE_TTL_MS }
            ?.let { return@withLock it.observation }
        runCatching { delegate.current(point) }
            .onSuccess { observation ->
                cached = CacheEntry(point, observation, now)
            }
            .getOrElse { error ->
                cached?.takeIf { it.point == point && now - it.fetchedAtEpochMillis < STALE_CACHE_TTL_MS }
                    ?.observation
                    ?: throw error
            }
    }

    private data class CacheEntry(
        val point: WeatherQueryPoint,
        val observation: WeatherObservation,
        val fetchedAtEpochMillis: Long,
    )

    private companion object {
        const val CACHE_TTL_MS = 45 * 60 * 1_000L
        const val STALE_CACHE_TTL_MS = 3 * 60 * 60 * 1_000L
    }
}

@Serializable
private data class OpenMeteoResponse(
    val current: OpenMeteoCurrent? = null,
)

@Serializable
private data class OpenMeteoCurrent(
    @SerialName("temperature_2m")
    val temperatureCelsius: Double,
    @SerialName("apparent_temperature")
    val apparentTemperatureCelsius: Double,
    @SerialName("relative_humidity_2m")
    val relativeHumidityPercent: Int,
    @SerialName("precipitation")
    val precipitationMillimeters: Double,
    @SerialName("weather_code")
    val weatherCode: Int,
    @SerialName("wind_speed_10m")
    val windSpeedKmh: Double,
)

private fun Double.requestCoordinate(): String = String.format(Locale.US, "%.1f", this)

internal fun weatherCodeLabel(code: Int): String = when (code) {
    0 -> "晴"
    1, 2 -> "少云"
    3 -> "阴"
    45, 48 -> "有雾"
    51, 53, 55, 56, 57 -> "毛毛雨"
    61, 63, 65, 66, 67 -> "有雨"
    71, 73, 75, 77 -> "有雪"
    80, 81, 82 -> "阵雨"
    85, 86 -> "阵雪"
    95, 96, 99 -> "雷雨"
    else -> "天气变化"
}
