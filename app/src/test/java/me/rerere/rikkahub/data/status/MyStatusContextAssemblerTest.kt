package me.rerere.rikkahub.data.status

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyStatusContextAssemblerTest {
    @Test
    fun weatherFailureOnlyRemovesEnvironmentWeatherFacts() = runBlocking {
        val assembler = MyStatusContextAssembler(
            locationProvider = object : MyStatusLocationProvider {
                override fun hasPermission(): Boolean = true

                override suspend fun current(): LocalStatusLocation = LocalStatusLocation(
                    modelContext = MyStatusLocationContext(
                        area = "杭州市",
                        observedAt = "2026-07-23T09:00:00Z",
                        confidence = MyStatusConfidence.HIGH,
                    ),
                    weatherQueryPoint = WeatherQueryPoint.fromRaw(30.2741, 120.1551),
                )
            },
            weatherProvider = object : WeatherProvider {
                override suspend fun current(point: WeatherQueryPoint): WeatherObservation {
                    error("weather unavailable")
                }
            },
            bodySource = MyStatusBodySource {
                MyStatusBodyFacts(
                    sleepMinutes = 420,
                    heartRateBpm = 70,
                    bloodOxygenPercent = 98,
                    steps = 3_000,
                    caloriesKcal = 300,
                    exerciseCount = 1,
                    observedAt = "2026-07-23T09:00:00Z",
                )
            },
            agendaSource = MyStatusAgendaSource {
                MyStatusAgendaFacts(
                    pendingCount = 1,
                    overdueCount = 0,
                    nextItems = emptyList(),
                    observedAt = "2026-07-23T09:00:00Z",
                )
            },
            clock = MyStatusClock { 1_774_406_400_000L },
        )

        val facts = assembler.collect()

        assertTrue(facts.weatherUnavailable)
        assertNull(facts.weather)
        assertEquals("杭州市", facts.location?.area)
        assertEquals(420, facts.body?.sleepMinutes)
        assertEquals(1, facts.agenda?.pendingCount)
        assertTrue(facts.evidence.any { it.id == "environment.location" })
        assertTrue(facts.evidence.any { it.id == "body.sleep" })
    }
}
