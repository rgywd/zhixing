package me.rerere.rikkahub.data.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

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
    fun modelPayloadCarriesExplicitLocalTimeZoneAndInterventionPolicy() {
        val now = ZonedDateTime.of(2026, 7, 24, 0, 17, 0, 0, ZONE)
            .toInstant()
            .toEpochMilli()
        val facts = sampleFacts().copy(
            observedAtEpochMillis = now,
            agenda = MyStatusAgendaFacts(
                pendingCount = 1,
                overdueCount = 0,
                nextItems = listOf(
                    MyStatusAgendaItem(
                        evidenceId = "agenda.item.undated",
                        title = "整理右侧面板",
                        timing = MyStatusAgendaTiming.UNDATED,
                    )
                ),
                observedAt = formatInstant(now),
            ),
        )
        val policy = buildMyStatusInterventionPolicy(facts, ZONE)

        val payload = encodeMyStatusModelInput(
            buildMyStatusModelInput(
                facts = facts,
                allowBodyInAiContext = false,
                zoneId = ZONE,
                interventionPolicy = policy,
            )
        )

        assertTrue(payload.contains("\"localDateTime\":\"2026-07-24T00:17:00+08:00\""))
        assertTrue(payload.contains("\"timeZoneId\":\"Asia/Shanghai\""))
        assertTrue(payload.contains("\"quietHours\":true"))
        assertTrue(payload.contains("\"recommendationAllowed\":false"))
    }

    @Test
    fun quietHoursRejectRecommendationBasedOnlyOnUndatedTodo() {
        val now = ZonedDateTime.of(2026, 7, 24, 0, 17, 0, 0, ZONE)
            .toInstant()
            .toEpochMilli()
        val facts = sampleFacts().copy(
            observedAtEpochMillis = now,
            agenda = MyStatusAgendaFacts(
                pendingCount = 1,
                overdueCount = 0,
                nextItems = listOf(
                    MyStatusAgendaItem(
                        evidenceId = "agenda.item.undated",
                        title = "整理右侧面板",
                        timing = MyStatusAgendaTiming.UNDATED,
                    )
                ),
                observedAt = formatInstant(now),
            ),
            evidence = listOf(
                MyStatusEvidence(
                    id = "agenda.item.undated",
                    kind = MyStatusInsightKind.AGENDA,
                    label = "事项 · 无截止时间",
                    value = "整理右侧面板",
                    freshness = "刚刚更新",
                )
            ),
        )
        val policy = buildMyStatusInterventionPolicy(facts, ZONE)
        val generated = MyStatusSnapshot(
            summary = "当前还有一项没有截止时间的安排。",
            insights = emptyList(),
            recommendation = MyStatusRecommendation(
                text = "先处理这项待办。",
                evidenceIds = listOf("agenda.item.undated"),
            ),
            evidence = facts.evidence,
            generatedAtEpochMillis = now,
            validUntilEpochMillis = now + MY_STATUS_VALIDITY_MS,
            confidence = MyStatusConfidence.MEDIUM,
            source = MyStatusSource.AI,
        )

        val guarded = enforceMyStatusInterventionPolicy(generated, policy)

        assertTrue(policy.quietHours)
        assertFalse(policy.recommendationAllowed)
        assertNull(guarded.recommendation)
    }

    @Test
    fun quietHoursAllowRecommendationForExplicitNearTermDeadline() {
        val now = ZonedDateTime.of(2026, 7, 24, 0, 17, 0, 0, ZONE)
            .toInstant()
            .toEpochMilli()
        val dueAt = now + 45 * 60 * 1_000L
        val facts = sampleFacts().copy(
            observedAtEpochMillis = now,
            agenda = MyStatusAgendaFacts(
                pendingCount = 1,
                overdueCount = 0,
                nextItems = listOf(
                    MyStatusAgendaItem(
                        evidenceId = "agenda.item.deadline",
                        title = "提交报名材料",
                        dueAt = formatInstant(dueAt),
                        timing = MyStatusAgendaTiming.DUE_SOON,
                    )
                ),
                observedAt = formatInstant(now),
            ),
        )

        val policy = buildMyStatusInterventionPolicy(facts, ZONE)

        assertTrue(policy.quietHours)
        assertTrue(policy.recommendationAllowed)
        assertEquals(listOf("agenda.item.deadline"), policy.allowedRecommendationEvidenceIds)
    }

    @Test
    fun quietHoursSurfaceOverdueFactsWithoutPushingImmediateWork() {
        val now = ZonedDateTime.of(2026, 7, 24, 0, 17, 0, 0, ZONE)
            .toInstant()
            .toEpochMilli()
        val facts = sampleFacts().copy(
            observedAtEpochMillis = now,
            agenda = MyStatusAgendaFacts(
                pendingCount = 1,
                overdueCount = 1,
                nextItems = listOf(
                    MyStatusAgendaItem(
                        evidenceId = "agenda.item.overdue",
                        title = "整理右侧面板",
                        dueAt = formatInstant(now - 60 * 60 * 1_000L),
                        timing = MyStatusAgendaTiming.OVERDUE,
                    )
                ),
                observedAt = formatInstant(now),
            ),
        )

        val fallback = buildLocalMyStatusFallback(facts, now, ZONE)

        assertEquals("有逾期事项需要留意，但此刻不必默认开始工作。", fallback.summary)
        assertNull(fallback.recommendation)
    }

    @Test
    fun ordinaryBodyReadingsAreEvidenceButNotPretendedAnalysis() {
        val facts = sampleFacts().copy(
            body = MyStatusBodyFacts(
                sleepMinutes = 480,
                heartRateBpm = 72,
                bloodOxygenPercent = 98,
                steps = 6_000,
                observedAt = "2026-07-23T09:00:00Z",
            ),
            agenda = MyStatusAgendaFacts(
                pendingCount = 1,
                overdueCount = 0,
                nextItems = listOf(
                    MyStatusAgendaItem(
                        evidenceId = "agenda.item.undated",
                        title = "整理右侧面板",
                        timing = MyStatusAgendaTiming.UNDATED,
                    )
                ),
                observedAt = "2026-07-23T09:00:00Z",
            ),
        )
        val policy = buildMyStatusInterventionPolicy(facts, ZONE)
        val generated = MyStatusSnapshot(
            summary = "当前状态平稳。",
            insights = listOf(
                MyStatusInsight(
                    kind = MyStatusInsightKind.BODY,
                    text = "最近一次心率是 72 bpm。",
                    evidenceIds = listOf("body.heartRate"),
                )
            ),
            recommendation = null,
            evidence = facts.evidence,
            generatedAtEpochMillis = NOW,
            validUntilEpochMillis = NOW + MY_STATUS_VALIDITY_MS,
            confidence = MyStatusConfidence.MEDIUM,
            source = MyStatusSource.AI,
        )

        val guarded = enforceMyStatusInterventionPolicy(generated, policy)

        assertFalse(policy.shouldGenerateInterpretation)
        assertTrue(guarded.insights.isEmpty())
    }

    @Test
    fun localFallbackDoesNotRepeatOrdinaryBodyWeatherOrUndatedAgendaFacts() {
        val facts = sampleFacts().copy(
            weather = sampleFacts().weather?.copy(
                apparentTemperatureCelsius = 27.0,
                precipitationMillimeters = 0.0,
            ),
            body = MyStatusBodyFacts(
                sleepMinutes = 480,
                heartRateBpm = 72,
                bloodOxygenPercent = 98,
                steps = 6_000,
                observedAt = "2026-07-23T09:00:00Z",
            ),
            agenda = MyStatusAgendaFacts(
                pendingCount = 1,
                overdueCount = 0,
                nextItems = listOf(
                    MyStatusAgendaItem(
                        evidenceId = "agenda.item.undated",
                        title = "整理右侧面板",
                        timing = MyStatusAgendaTiming.UNDATED,
                    )
                ),
                observedAt = "2026-07-23T09:00:00Z",
            ),
        )

        val fallback = buildLocalMyStatusFallback(facts, NOW, ZONE)

        assertTrue(fallback.insights.isEmpty())
        assertNull(fallback.recommendation)
        assertEquals("目前没有明显需要调整的信号，可以按自己的节奏安排。", fallback.summary)
    }

    @Test
    fun activeProfilesEnterPersonalContextWithoutObservations() {
        val memories = listOf(
            AssistantMemory(
                id = 1,
                content = "用户偏好深夜不处理普通工作。",
                kind = MemoryKind.PROFILE,
                state = MemoryState.ACTIVE,
                dimensionId = "preferences_values",
            ),
            AssistantMemory(
                id = 2,
                content = "一次性的临时观察。",
                kind = MemoryKind.OBSERVATION,
                state = MemoryState.ACTIVE,
                dimensionId = "preferences_values",
            ),
            AssistantMemory(
                id = 3,
                content = "已归档画像。",
                kind = MemoryKind.PROFILE,
                state = MemoryState.ARCHIVED,
                dimensionId = "behavior_collaboration",
            ),
        )

        val personalContext = buildMyStatusPersonalContext(memories)

        assertEquals(1, personalContext.size)
        assertEquals("preferences_values", personalContext.single().dimensionId)
        assertEquals("用户偏好深夜不处理普通工作。", personalContext.single().content)
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
            nextItems = listOf(
                MyStatusAgendaItem(
                    evidenceId = "agenda.item.review",
                    title = "项目回顾",
                    dueAt = "2026-07-23T12:00:00Z",
                    timing = MyStatusAgendaTiming.UPCOMING,
                )
            ),
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
