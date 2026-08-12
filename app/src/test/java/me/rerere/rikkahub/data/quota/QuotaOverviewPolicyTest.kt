package me.rerere.rikkahub.data.quota

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class QuotaOverviewPolicyTest {
    @Test
    fun `all windows stay visible and codex groups sort short window first`() {
        val codex = buildQuotaOverviews(
            envelope(
                snapshot(
                    id = "codex-a",
                    provider = "codex",
                    windows = listOf(
                        window("code-primary", "Weekly limit", 84.0, 604_800),
                        window("code-secondary", "5-hour limit", 63.0, 18_000),
                        window(
                            "additional-gpt-5-3-codex-spark-primary",
                            "Weekly limit",
                            19.0,
                            604_800,
                        ),
                        window(
                            "additional-gpt-5-3-codex-spark-secondary",
                            "5-hour limit",
                            47.0,
                            18_000,
                        ),
                    ),
                ),
            ),
            NOW,
        ).first { it.provider == "codex" }

        assertEquals(4, codex.windowCount)
        assertEquals(1, codex.lowWindowCount)
        assertEquals(19.0, codex.remainingPercent)
        assertEquals(
            listOf(
                "code-secondary",
                "code-primary",
                "additional-gpt-5-3-codex-spark-secondary",
                "additional-gpt-5-3-codex-spark-primary",
            ),
            codex.accounts.single().windows.map { it.key },
        )
        assertEquals(
            listOf("标准模型", "标准模型", "额外 · GPT-5.3 Codex Spark", "额外 · GPT-5.3 Codex Spark"),
            codex.accounts.single().windows.map { it.groupLabel },
        )
    }

    @Test
    fun `multiple accounts remain separate without adding or dropping windows`() {
        val failed = snapshot(
            id = "codex-b",
            provider = "codex",
            windows = emptyList(),
        ).copy(
            state = QuotaState.ERROR,
            error = QuotaCollectionError("upstream_error", "failed", true),
        )
        val codex = buildQuotaOverviews(
            envelope(
                snapshot(
                    id = "codex-a",
                    provider = "codex",
                    windows = listOf(
                        window("code-primary", "Weekly limit", 84.0, 604_800),
                        window("code-secondary", "5-hour limit", 17.0, 18_000),
                    ),
                ),
                failed,
                snapshot(
                    id = "future-a",
                    provider = "future",
                    windows = listOf(window("rolling", "Rolling limit", 66.0, null)),
                ),
            ),
            NOW,
        ).first { it.provider == "codex" }

        assertEquals(2, codex.accountCount)
        assertEquals(1, codex.usableAccountCount)
        assertEquals(listOf("codex-a", "codex-b"), codex.accounts.map { it.credentialId })
        assertEquals(listOf(2, 0), codex.accounts.map { it.windows.size })
        assertEquals(2, codex.windowCount)
        assertEquals(17.0, codex.remainingPercent)
        assertEquals(ProviderQuotaStatus.PARTIAL, codex.status)
    }

    @Test
    fun `selected channel moves to the front without changing provider membership`() {
        val items = buildQuotaOverviews(
            envelope(
                snapshot(
                    id = "codex-a",
                    provider = "codex",
                    windows = listOf(window("weekly", "Weekly limit", 84.0, 604_800)),
                ),
                snapshot(
                    id = "kimi-a",
                    provider = "kimi",
                    windows = listOf(window("weekly", "Weekly limit", 66.0, 604_800)),
                ),
            ),
            NOW,
        )

        assertEquals(
            listOf("kimi", "codex"),
            orderQuotaChannels(items, "kimi").map { it.provider },
        )
        assertEquals(items, orderQuotaChannels(items, "missing"))
    }

    @Test
    fun `providers removed from the latest inventory disappear from the overview`() {
        val items = buildQuotaOverviews(
            envelope(
                snapshot(
                    id = "codex-a",
                    provider = "codex",
                    windows = listOf(window("weekly", "Weekly limit", 84.0, 604_800)),
                ),
            ),
            NOW,
        )

        assertEquals(listOf("codex"), items.map { it.provider })
        assertEquals(emptyList<ProviderQuotaOverview>(), buildQuotaOverviews(envelope(), NOW))
    }

    @Test
    fun `errors and null quota stay unknown instead of becoming zero`() {
        val errorSnapshot = snapshot(
            id = "kimi-a",
            provider = "kimi",
            windows = listOf(window("weekly", "Weekly limit", null, 604_800)),
        ).copy(
            state = QuotaState.ERROR,
            error = QuotaCollectionError("upstream_error", "failed", true),
        )
        val kimi = buildQuotaOverviews(envelope(errorSnapshot), NOW).first { it.provider == "kimi" }

        assertNull(kimi.remainingPercent)
        assertEquals(0, kimi.windowCount)
        assertEquals(ProviderQuotaStatus.ERROR, kimi.status)
    }

    @Test
    fun `proxy fallback is visibly stale`() {
        val codex = buildQuotaOverviews(
            envelope(
                snapshot(
                    id = "codex-a",
                    provider = "codex",
                    windows = listOf(window("weekly", "Weekly limit", 70.0, 604_800)),
                ),
            ).copy(proxyStale = true),
            NOW,
        ).first { it.provider == "codex" }

        assertEquals(ProviderQuotaStatus.STALE, codex.status)
    }

    @Test
    fun `invalid percentages are rejected at the Android boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            envelope(
                snapshot(
                    id = "codex-a",
                    provider = "codex",
                    windows = listOf(window("weekly", "Weekly limit", 101.0, 604_800)),
                ),
            ).requireValid()
        }
    }

    private fun envelope(vararg snapshots: QuotaSnapshot) = QuotaEnvelope(
        schemaVersion = QUOTA_SCHEMA_VERSION,
        generatedAt = "2026-07-22T06:40:00Z",
        staleAfterSeconds = 1200,
        items = snapshots.toList(),
    )

    private fun snapshot(
        id: String,
        provider: String,
        windows: List<QuotaWindow>,
    ) = QuotaSnapshot(
        credentialId = id,
        provider = provider,
        label = id,
        state = QuotaState.OK,
        sourceStatus = "active",
        windows = windows,
        metadata = JsonObject(emptyMap()),
        checkedAt = "2026-07-22T06:37:18Z",
    )

    private fun window(
        key: String,
        label: String,
        remainingPercent: Double?,
        windowSeconds: Long?,
    ) = QuotaWindow(
        key = key,
        label = label,
        remainingPercent = remainingPercent,
        unit = "quota",
        resetAt = "2026-07-27T01:38:25Z",
        windowSeconds = windowSeconds,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-22T06:40:01Z")
    }
}
