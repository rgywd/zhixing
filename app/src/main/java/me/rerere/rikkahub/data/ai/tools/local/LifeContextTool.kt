package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.status.CollectedMyStatusFacts
import me.rerere.rikkahub.data.status.MyStatusContextAssembler
import me.rerere.rikkahub.data.status.buildMyStatusModelInput
import me.rerere.rikkahub.data.status.formatInstant
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

internal const val LIFE_CONTEXT_TOOL_NAME = "get_life_context"
internal const val LIFE_CONTEXT_SCHEMA = "zhixing.life-context.v1"

class LifeContextProvider internal constructor(
    private val contextAssembler: MyStatusContextAssembler,
) {
    internal suspend fun collect(): CollectedMyStatusFacts = contextAssembler.collect()

    internal fun hasLocationPermission(): Boolean = !contextAssembler.locationPermissionRequired()
}

internal fun buildLifeContextTool(
    provider: LifeContextProvider,
    allowAiHealthData: () -> Boolean,
): Tool = buildLifeContextTool(
    collectFacts = provider::collect,
    hasLocationPermission = provider::hasLocationPermission,
    allowAiHealthData = allowAiHealthData,
)

internal fun buildLifeContextTool(
    collectFacts: suspend () -> CollectedMyStatusFacts,
    hasLocationPermission: () -> Boolean,
    allowAiHealthData: () -> Boolean,
    zoneId: () -> ZoneId = ZoneId::systemDefault,
): Tool = Tool(
    name = LIFE_CONTEXT_TOOL_NAME,
    description = """
        Get the user's current life context from local-first sources. Returns the device-local collection time and
        timezone; coarse province/city/district-or-county area and current weather facts when location permission is
        available; latest connected-wearable sleep, heart rate, blood oxygen, steps, calories, and exercise facts only
        when AI health-data sharing is enabled; and a compact agenda snapshot with pending/overdue counts and up to
        three relevant tasks or plan stages. Fields may be absent; source observation times are returned when available.
        Never infer missing values. This tool is read-only and never returns coordinates, street-level location, profile
        memory, raw sensor history, financial data, screen time, inbox content, or full task/calendar history. Use it
        when the user's request depends on how they are doing or what is relevant around them now.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { },
            additionalProperties = false,
        )
    },
    sanitizeInputForStorage = { "{}" },
    needsApproval = { false },
    executionMode = ToolExecutionMode.PARALLEL_READ_ONLY,
    execute = {
        try {
            val locationPermissionGranted = hasLocationPermission()
            val healthDataEnabled = allowAiHealthData()
            val facts = collectFacts()
            val payload = encodeLifeContext(
                facts = facts,
                locationPermissionGranted = locationPermissionGranted,
                healthDataEnabled = healthDataEnabled,
                zoneId = zoneId(),
            )
            listOf(UIMessagePart.Text(payload))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("schema", LIFE_CONTEXT_SCHEMA)
                        put("success", false)
                        put("error", buildJsonObject {
                            put("code", "LIFE_CONTEXT_UNAVAILABLE")
                            put("message", "Current life context is temporarily unavailable")
                        })
                    }.toString()
                )
            )
        }
    },
)

internal fun encodeLifeContext(
    facts: CollectedMyStatusFacts,
    locationPermissionGranted: Boolean,
    healthDataEnabled: Boolean,
    zoneId: ZoneId,
): String {
    val filtered = buildMyStatusModelInput(
        facts = facts,
        allowBodyInAiContext = healthDataEnabled,
        zoneId = zoneId,
    )
    val collectedAt = Instant.ofEpochMilli(facts.observedAtEpochMillis)
    val localDateTime = ZonedDateTime.ofInstant(collectedAt, zoneId)
    val warnings = buildList {
        if (!locationPermissionGranted) add("LOCATION_PERMISSION_REQUIRED")
        if (locationPermissionGranted && filtered.location == null) add("LOCATION_UNAVAILABLE")
        if (facts.weatherUnavailable) add("WEATHER_UNAVAILABLE")
        if (healthDataEnabled && filtered.body == null) add("BODY_DATA_UNAVAILABLE")
        if (filtered.agenda == null) add("AGENDA_DATA_UNAVAILABLE")
    }

    return buildJsonObject {
        put("schema", LIFE_CONTEXT_SCHEMA)
        put("success", true)
        put("collectedAt", formatInstant(facts.observedAtEpochMillis))
        put("localDateTime", localDateTime.toString())
        put("timeZoneId", zoneId.id)
        put("availability", buildJsonObject {
            put("locationPermissionGranted", locationPermissionGranted)
            put("aiHealthDataEnabled", healthDataEnabled)
            put("locationAvailable", filtered.location != null)
            put("weatherAvailable", filtered.weather != null)
            put("bodyAvailable", filtered.body != null)
            put("agendaAvailable", filtered.agenda != null)
        })
        filtered.location?.let { location ->
            put("location", buildJsonObject {
                put("area", location.area.replace(" · ", ""))
                put("granularity", location.granularity.name.lowercase(Locale.ROOT))
                put("confidence", location.confidence.name.lowercase(Locale.ROOT))
                put("observedAt", location.observedAt)
            })
        }
        filtered.weather?.let { weather ->
            put("weather", buildJsonObject {
                put("condition", weather.condition)
                put("temperatureCelsius", weather.temperatureCelsius)
                put("apparentTemperatureCelsius", weather.apparentTemperatureCelsius)
                put("relativeHumidityPercent", weather.relativeHumidityPercent)
                put("precipitationMillimeters", weather.precipitationMillimeters)
                put("windSpeedKmh", weather.windSpeedKmh)
                put("observedAt", weather.observedAt)
            })
        }
        filtered.body?.let { body ->
            put("body", buildJsonObject {
                body.sleepMinutes?.let { put("sleepMinutes", it) }
                body.deepSleepMinutes?.let { put("deepSleepMinutes", it) }
                body.heartRateBpm?.let { put("heartRateBpm", it) }
                body.bloodOxygenPercent?.let { put("bloodOxygenPercent", it) }
                body.steps?.let { put("steps", it) }
                body.caloriesKcal?.let { put("caloriesKcal", it) }
                body.exerciseCount?.let { put("exerciseCount", it) }
                body.observedAt?.let { put("observedAt", it) }
            })
        }
        filtered.agenda?.let { agenda ->
            put("agenda", buildJsonObject {
                put("pendingCount", agenda.pendingCount)
                put("overdueCount", agenda.overdueCount)
                put("observedAt", agenda.observedAt)
                put("nextItems", buildJsonArray {
                    agenda.nextItems.take(3).forEach { item ->
                        add(buildJsonObject {
                            put("title", item.title)
                            item.dueAt?.let { put("dueAt", it) }
                            put("timing", item.timing.name.lowercase(Locale.ROOT))
                        })
                    }
                })
            })
        }
        put("warnings", buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } })
    }.toString()
}
