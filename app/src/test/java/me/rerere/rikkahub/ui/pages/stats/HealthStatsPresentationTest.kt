package me.rerere.rikkahub.ui.pages.stats

import java.time.Instant
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchHealthSnapshot
import me.rerere.rikkahub.data.device.lenovo.LenovoWatchProbeState
import me.rerere.rikkahub.data.model.HealthMetricRecord
import me.rerere.rikkahub.data.model.HealthMetricSourceType
import me.rerere.rikkahub.data.model.HealthMetricType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthStatsPresentationTest {
    @Test
    fun `keeps newest record per metric and calculates BMI from explicit height and weight`() {
        val state = buildHealthStatsUiState(
            records = listOf(
                record("old-weight", HealthMetricType.WEIGHT_KG, "74", 100L),
                record("height", HealthMetricType.HEIGHT_CM, "180", 200L),
                record("weight", HealthMetricType.WEIGHT_KG, "72.9", 300L),
            ),
            watchState = LenovoWatchProbeState(),
        )

        assertEquals("72.9", state.metrics.getValue(HealthMetricType.WEIGHT_KG).valueDecimal)
        assertEquals("22.5", state.metrics.getValue(HealthMetricType.BMI).valueDecimal)
        assertTrue(state.metrics.getValue(HealthMetricType.BMI).calculated)
        assertEquals(2, state.history.getValue(HealthMetricType.WEIGHT_KG).size)
    }

    @Test
    fun `explicit BMI wins and watch metrics retain watch source`() {
        val syncAt = Instant.ofEpochMilli(1_000L)
        val state = buildHealthStatsUiState(
            records = listOf(record("bmi", HealthMetricType.BMI, "23.1", 2_000L)),
            watchState = LenovoWatchProbeState(
                lastSuccessfulSyncAt = syncAt,
                health = LenovoWatchHealthSnapshot(
                    steps = 8_234,
                    heartRate = 68,
                    bloodOxygen = 98,
                ),
            ),
        )

        assertEquals("23.1", state.metrics.getValue(HealthMetricType.BMI).valueDecimal)
        assertFalse(state.metrics.getValue(HealthMetricType.BMI).calculated)
        assertEquals(
            HealthMetricSourceType.LENOVO_WATCH,
            state.metrics.getValue(HealthMetricType.STEPS).sourceType,
        )
        assertTrue(state.hasAnyData)
    }

    private fun record(
        id: String,
        type: HealthMetricType,
        value: String,
        effectiveAt: Long,
    ) = HealthMetricRecord(
        id = id,
        type = type,
        valueDecimal = value,
        unit = type.canonicalUnit,
        observedAtEpochMillis = effectiveAt,
        recordedAtEpochMillis = effectiveAt,
        sourceType = HealthMetricSourceType.AI_EXTRACTED_CHAT,
        sourceConversationId = "conversation-1",
        sourceMessageId = "message-1",
    )
}
