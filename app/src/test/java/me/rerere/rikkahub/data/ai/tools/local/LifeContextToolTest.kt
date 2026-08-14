package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.status.CollectedMyStatusFacts
import me.rerere.rikkahub.data.status.MyStatusAgendaFacts
import me.rerere.rikkahub.data.status.MyStatusAgendaItem
import me.rerere.rikkahub.data.status.MyStatusAgendaTiming
import me.rerere.rikkahub.data.status.MyStatusBodyFacts
import me.rerere.rikkahub.data.status.MyStatusConfidence
import me.rerere.rikkahub.data.status.MyStatusLocationContext
import me.rerere.rikkahub.data.status.MyStatusLocationGranularity
import me.rerere.rikkahub.data.status.MyStatusWeatherFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LifeContextToolTest {
    @Test
    fun `tool is parameterless read only and tells the model exactly what it contains`() {
        val tool = tool()
        val schema = tool.parameters() as InputSchema.Obj

        assertEquals(LIFE_CONTEXT_TOOL_NAME, tool.name)
        assertEquals(false, schema.additionalProperties)
        assertTrue(schema.properties.isEmpty())
        assertEquals(ToolExecutionMode.PARALLEL_READ_ONLY, tool.executionMode)
        assertFalse(tool.needsApproval(buildJsonObject { }))
        assertEquals("{}", tool.sanitizeInputForStorage("""{"secret":"must disappear"}"""))
        assertTrue(tool.description.contains("province/city/district-or-county"))
        assertTrue(tool.description.contains("sleep, heart rate, blood oxygen, steps, calories, and exercise"))
        assertTrue(tool.description.contains("pending/overdue counts"))
        assertTrue(tool.description.contains("never returns coordinates"))
    }

    @Test
    fun `live result contains compact district weather body and bounded agenda without technical ids`() = runBlocking {
        val result = tool(healthEnabled = true).execute(buildJsonObject { }).json()

        assertEquals(LIFE_CONTEXT_SCHEMA, result.getValue("schema").jsonPrimitive.content)
        assertTrue(result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals("2026-08-14T17:00+08:00[Asia/Shanghai]", result.getValue("localDateTime").jsonPrimitive.content)
        val location = result.getValue("location").jsonObject
        assertEquals("北京市朝阳区", location.getValue("area").jsonPrimitive.content)
        assertEquals("district", location.getValue("granularity").jsonPrimitive.content)
        assertEquals("high", location.getValue("confidence").jsonPrimitive.content)
        assertEquals(33.2, result.getValue("weather").jsonObject
            .getValue("apparentTemperatureCelsius").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(72, result.getValue("body").jsonObject
            .getValue("heartRateBpm").jsonPrimitive.content.toInt())
        assertEquals(3, result.getValue("agenda").jsonObject
            .getValue("nextItems").jsonArray.size)
        assertTrue(result.getValue("warnings").jsonArray.isEmpty())

        val encoded = result.toString()
        assertFalse(encoded.contains("latitude", ignoreCase = true))
        assertFalse(encoded.contains("longitude", ignoreCase = true))
        assertFalse(encoded.contains("evidenceId"))
        assertFalse(encoded.contains("profile"))
        assertFalse(encoded.contains("朝阳路"))
    }

    @Test
    fun `health setting removes every body field while preserving other life facts`() = runBlocking {
        val result = tool(healthEnabled = false).execute(buildJsonObject { }).json()

        assertFalse("body" in result)
        val availability = result.getValue("availability").jsonObject
        assertFalse(availability.getValue("aiHealthDataEnabled").jsonPrimitive.content.toBoolean())
        assertFalse(availability.getValue("bodyAvailable").jsonPrimitive.content.toBoolean())
        assertTrue("weather" in result)
        assertTrue("agenda" in result)
        assertFalse(result.toString().contains("heartRateBpm"))
    }

    @Test
    fun `missing permission and partial source failures are explicit without inventing data`() = runBlocking {
        val facts = sampleFacts().copy(
            location = null,
            weather = null,
            body = null,
            agenda = null,
            weatherUnavailable = true,
        )
        val result = tool(
            healthEnabled = true,
            locationPermissionGranted = false,
            facts = facts,
        ).execute(buildJsonObject { }).json()
        val warnings = result.getValue("warnings").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(
            listOf(
                "LOCATION_PERMISSION_REQUIRED",
                "WEATHER_UNAVAILABLE",
                "BODY_DATA_UNAVAILABLE",
                "AGENDA_DATA_UNAVAILABLE",
            ),
            warnings,
        )
        assertFalse("location" in result)
        assertFalse("weather" in result)
        assertFalse("body" in result)
        assertFalse("agenda" in result)
    }

    @Test
    fun `cancellation propagates and other failures hide exception details`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                failingTool(CancellationException("cancelled")).execute(buildJsonObject { })
            }
        }

        val result = runBlocking {
            failingTool(IllegalStateException("private coordinates and health history"))
                .execute(buildJsonObject { })
                .json()
        }
        assertFalse(result.getValue("success").jsonPrimitive.content.toBoolean())
        assertEquals(
            "LIFE_CONTEXT_UNAVAILABLE",
            result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
        )
        assertFalse(result.toString().contains("coordinates"))
        assertFalse(result.toString().contains("health history"))
    }

    private fun tool(
        healthEnabled: Boolean = true,
        locationPermissionGranted: Boolean = true,
        facts: CollectedMyStatusFacts = sampleFacts(),
    ) = buildLifeContextTool(
        collectFacts = { facts },
        hasLocationPermission = { locationPermissionGranted },
        allowAiHealthData = { healthEnabled },
        zoneId = { ZONE },
    )

    private fun failingTool(error: Throwable) = buildLifeContextTool(
        collectFacts = { throw error },
        hasLocationPermission = { true },
        allowAiHealthData = { true },
        zoneId = { ZONE },
    )

    private fun sampleFacts() = CollectedMyStatusFacts(
        observedAtEpochMillis = NOW,
        location = MyStatusLocationContext(
            area = "北京市 · 朝阳区",
            observedAt = "2026-08-14T08:58:00Z",
            confidence = MyStatusConfidence.HIGH,
            granularity = MyStatusLocationGranularity.DISTRICT,
        ),
        weather = MyStatusWeatherFacts(
            condition = "多云",
            temperatureCelsius = 31.0,
            apparentTemperatureCelsius = 33.2,
            relativeHumidityPercent = 65,
            precipitationMillimeters = 0.0,
            windSpeedKmh = 9.0,
            observedAt = "2026-08-14T08:59:00Z",
        ),
        body = MyStatusBodyFacts(
            sleepMinutes = 420,
            deepSleepMinutes = 90,
            heartRateBpm = 72,
            bloodOxygenPercent = 98,
            steps = 4_200,
            caloriesKcal = 320,
            exerciseCount = 1,
            observedAt = "2026-08-14T08:55:00Z",
        ),
        agenda = MyStatusAgendaFacts(
            pendingCount = 4,
            overdueCount = 1,
            nextItems = (1..4).map { index ->
                MyStatusAgendaItem(
                    evidenceId = "agenda.item.$index",
                    title = "事项 $index",
                    dueAt = "2026-08-14T1${index}:00:00Z",
                    timing = MyStatusAgendaTiming.TODAY,
                )
            },
            observedAt = "2026-08-14T09:00:00Z",
        ),
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        const val NOW = 1_786_698_000_000L
    }
}

private fun List<UIMessagePart>.json() = Json.parseToJsonElement(
    (single() as UIMessagePart.Text).text,
).jsonObject
