package me.rerere.rikkahub.data.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MyStatusModelsTest {
    @Test
    fun bodyFactsAreAbsentFromModelPayloadWhenSettingIsOff() {
        val facts = sampleFacts()

        val input = buildMyStatusModelInput(facts, allowBodyInAiContext = false, ZONE)
        val payload = encodeMyStatusModelInput(input)
        val root = Json.parseToJsonElement(payload).jsonObject

        assertFalse(root.containsKey("body"))
        assertFalse(payload.contains("heartRateBpm"))
        assertFalse(payload.contains("body.heartRate"))
        assertTrue(root.containsKey("weather"))
    }

    @Test
    fun bodyFactsEnterModelPayloadOnlyWhenSettingIsOn() {
        val input = buildMyStatusModelInput(sampleFacts(), allowBodyInAiContext = true, ZONE)
        val payload = encodeMyStatusModelInput(input)
        val root = Json.parseToJsonElement(payload).jsonObject

        assertTrue(root.containsKey("body"))
        assertTrue(payload.contains("\"heartRateBpm\":72"))
        assertTrue(payload.contains("body.heartRate"))
    }

    @Test
    fun modelLocationContainsOnlyAdministrativeAreaAndNeverCoordinates() {
        val rawLatitude = 30.2741
        val rawLongitude = 120.1551
        val point = WeatherQueryPoint.fromRaw(rawLatitude, rawLongitude)
        val area = buildAreaLabel(
            GeocodedArea(
                countryName = "中国",
                adminArea = "浙江省",
                locality = "杭州市",
                subAdminArea = "滨江区",
            )
        )
        val facts = sampleFacts().copy(
            location = MyStatusLocationContext(
                area = requireNotNull(area),
                observedAt = "2026-07-23T09:00:00Z",
                confidence = MyStatusConfidence.HIGH,
            )
        )

        val payload = encodeMyStatusModelInput(buildMyStatusModelInput(facts, false, ZONE))

        assertEquals(30.3, point.requestLatitude, 0.0)
        assertEquals(120.2, point.requestLongitude, 0.0)
        assertEquals("WeatherQueryPoint(redacted)", point.toString())
        assertTrue(payload.contains("浙江省 · 杭州市 · 滨江区"))
        assertFalse(payload.contains("latitude", ignoreCase = true))
        assertFalse(payload.contains("longitude", ignoreCase = true))
        assertFalse(payload.contains(rawLatitude.toString()))
        assertFalse(payload.contains(rawLongitude.toString()))
        assertFalse(payload.contains("街道"))
        assertFalse(payload.contains("门牌"))
    }

    @Test
    fun structuredResponseAcceptsFencedJsonAndCapsInsights() {
        val evidence = sampleFacts().evidence
        val raw = """
            ```json
            {
              "summary":"今天恢复稍弱，适合保持平稳节奏。",
              "summaryEvidenceIds":["body.sleep"],
              "insights":[
                {"category":"body","text":"昨晚睡眠偏短。","evidenceIds":["body.sleep"]},
                {"category":"environment","text":"当前体感偏热。","evidenceIds":["weather.apparentTemperature"]},
                {"category":"agenda","text":"还有待办。","evidenceIds":["agenda.pending"]}
              ],
              "recommendation":{"text":"先缩短下一段高强度安排。","evidenceIds":["body.sleep"]},
              "confidence":"high"
            }
            ```
        """.trimIndent()

        val snapshot = parseGeneratedMyStatus(raw, evidence, NOW, "杭州市")

        assertNotNull(snapshot)
        assertEquals(MyStatusSource.AI, snapshot?.source)
        assertEquals(2, snapshot?.insights?.size)
        assertEquals(MyStatusConfidence.HIGH, snapshot?.confidence)
        assertEquals(NOW + MY_STATUS_VALIDITY_MS, snapshot?.validUntilEpochMillis)
    }

    @Test
    fun structuredResponseRejectsInventedEvidenceAndMedicalDiagnosis() {
        val evidence = sampleFacts().evidence
        val invented = """
            {
              "summary":"今天状态稳定。",
              "summaryEvidenceIds":["body.unknown"],
              "insights":[],
              "confidence":"medium"
            }
        """.trimIndent()
        val diagnosis = """
            {
              "summary":"你已经确诊某种疾病。",
              "summaryEvidenceIds":["body.sleep"],
              "insights":[],
              "confidence":"high"
            }
        """.trimIndent()

        assertNull(parseGeneratedMyStatus(invented, evidence, NOW, null))
        assertNull(parseGeneratedMyStatus(diagnosis, evidence, NOW, null))
    }

    @Test
    fun fingerprintIgnoresSmallWeatherJitterButChangesAcrossSignificantBucket() {
        val base = sampleFacts()
        val slight = base.copy(
            weather = base.weather?.copy(apparentTemperatureCelsius = 31.8)
        )
        val significant = base.copy(
            weather = base.weather?.copy(apparentTemperatureCelsius = 33.2)
        )

        assertEquals(
            significantStatusFingerprint(base, true),
            significantStatusFingerprint(slight, true),
        )
        assertFalse(
            significantStatusFingerprint(base, true) ==
                significantStatusFingerprint(significant, true)
        )
    }

    @Test
    fun refreshPolicyDebouncesEventsAndFallsBackToHourlyRefresh() {
        val fingerprint = "stable"

        assertFalse(
            shouldRefreshMyStatus(
                previousFingerprint = fingerprint,
                currentFingerprint = "changed",
                lastAttemptAtEpochMillis = NOW - 5_000,
                lastSuccessfulAtEpochMillis = NOW - MY_STATUS_VALIDITY_MS,
                nowEpochMillis = NOW,
            )
        )
        assertTrue(
            shouldRefreshMyStatus(
                previousFingerprint = fingerprint,
                currentFingerprint = "changed",
                lastAttemptAtEpochMillis = NOW - MY_STATUS_REFRESH_DEBOUNCE_MS,
                lastSuccessfulAtEpochMillis = NOW - 1_000,
                nowEpochMillis = NOW,
            )
        )
        assertTrue(
            shouldRefreshMyStatus(
                previousFingerprint = fingerprint,
                currentFingerprint = fingerprint,
                lastAttemptAtEpochMillis = NOW - MY_STATUS_VALIDITY_MS,
                lastSuccessfulAtEpochMillis = NOW - MY_STATUS_VALIDITY_MS,
                nowEpochMillis = NOW,
            )
        )
    }

    @Test
    fun semanticKeyDoesNotChangeForFreshnessOnlyUpdates() {
        val original = buildLocalMyStatusFallback(sampleFacts(), NOW, ZONE)
        val refreshed = original.copy(
            generatedAtEpochMillis = NOW + 60_000,
            validUntilEpochMillis = NOW + MY_STATUS_VALIDITY_MS + 60_000,
        )

        val stable = stabilizeMyStatusSnapshot(original, refreshed)

        assertEquals(original.semanticContentKey(), stable.semanticContentKey())
        assertSame(original.summary, stable.summary)
        assertSame(original.insights, stable.insights)
        assertEquals(refreshed.generatedAtEpochMillis, stable.generatedAtEpochMillis)
    }

    private fun sampleFacts(): CollectedMyStatusFacts = CollectedMyStatusFacts(
        observedAtEpochMillis = NOW,
        location = MyStatusLocationContext(
            area = "杭州市",
            observedAt = "2026-07-23T09:00:00Z",
            confidence = MyStatusConfidence.HIGH,
        ),
        weather = MyStatusWeatherFacts(
            condition = "多云",
            temperatureCelsius = 30.0,
            apparentTemperatureCelsius = 31.1,
            relativeHumidityPercent = 68,
            precipitationMillimeters = 0.0,
            windSpeedKmh = 8.0,
            observedAt = "2026-07-23T09:00:00Z",
        ),
        body = MyStatusBodyFacts(
            sleepMinutes = 330,
            deepSleepMinutes = 80,
            heartRateBpm = 72,
            bloodOxygenPercent = 98,
            steps = 2_400,
            caloriesKcal = 310,
            exerciseCount = 1,
            observedAt = "2026-07-23T08:55:00Z",
        ),
        agenda = MyStatusAgendaFacts(
            pendingCount = 2,
            overdueCount = 0,
            nextItems = listOf(MyStatusAgendaItem("项目回顾", "2026-07-23T12:00:00Z")),
            observedAt = "2026-07-23T09:00:00Z",
        ),
        evidence = listOf(
            MyStatusEvidence(
                id = "body.sleep",
                kind = MyStatusInsightKind.BODY,
                label = "睡眠",
                value = "5小时30分",
                observedAt = "2026-07-23T08:55:00Z",
                freshness = "5 分钟前",
            ),
            MyStatusEvidence(
                id = "body.heartRate",
                kind = MyStatusInsightKind.BODY,
                label = "心率",
                value = "72 bpm",
                observedAt = "2026-07-23T08:55:00Z",
                freshness = "5 分钟前",
            ),
            MyStatusEvidence(
                id = "weather.apparentTemperature",
                kind = MyStatusInsightKind.ENVIRONMENT,
                label = "体感温度",
                value = "31.1℃",
                observedAt = "2026-07-23T09:00:00Z",
                freshness = "刚刚更新",
            ),
            MyStatusEvidence(
                id = "agenda.pending",
                kind = MyStatusInsightKind.AGENDA,
                label = "待处理",
                value = "2 项",
                observedAt = "2026-07-23T09:00:00Z",
                freshness = "刚刚更新",
            ),
        ),
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        const val NOW = 1_774_406_400_000L
    }
}
