package me.rerere.rikkahub.data.quota

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class QuotaOverviewPolicyTest {
    @Test
    fun `multiple accounts use the tightest window without adding percentages`() {
        val result = buildQuotaOverviews(
            envelope(
                snapshot("codex-a", "codex", 84.0),
                snapshot("codex-b", "codex", 17.0),
                snapshot("future-a", "future", 66.0),
            ),
            NOW,
        )

        val codex = result.first { it.provider == "codex" }
        assertEquals(2, codex.accountCount)
        assertEquals(17.0, codex.remainingPercent)
        assertEquals(ProviderQuotaStatus.LOW, codex.status)
        assertEquals(listOf("codex", "kimi", "xai", "future"), result.map { it.provider })
    }

    @Test
    fun `errors and null quota stay unknown instead of becoming zero`() {
        val errorSnapshot = snapshot("kimi-a", "kimi", null).copy(
            state = QuotaState.ERROR,
            error = QuotaCollectionError("upstream_error", "failed", true),
        )
        val kimi = buildQuotaOverviews(envelope(errorSnapshot), NOW).first { it.provider == "kimi" }

        assertNull(kimi.remainingPercent)
        assertEquals(ProviderQuotaStatus.ERROR, kimi.status)
    }

    @Test
    fun `proxy fallback is visibly stale`() {
        val codex = buildQuotaOverviews(
            envelope(snapshot("codex-a", "codex", 70.0)).copy(proxyStale = true),
            NOW,
        ).first { it.provider == "codex" }

        assertEquals(ProviderQuotaStatus.STALE, codex.status)
    }

    @Test
    fun `invalid percentages are rejected at the Android boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            envelope(snapshot("codex-a", "codex", 101.0)).requireValid()
        }
    }

    private fun envelope(vararg snapshots: QuotaSnapshot) = QuotaEnvelope(
        schemaVersion = QUOTA_SCHEMA_VERSION,
        generatedAt = "2026-07-22T06:40:00Z",
        staleAfterSeconds = 1200,
        items = snapshots.toList(),
    )

    private fun snapshot(id: String, provider: String, remainingPercent: Double?) = QuotaSnapshot(
        credentialId = id,
        provider = provider,
        label = id,
        state = QuotaState.OK,
        sourceStatus = "active",
        windows = listOf(
            QuotaWindow(
                key = "weekly",
                label = "每周额度",
                remainingPercent = remainingPercent,
                unit = "quota",
                resetAt = "2026-07-27T01:38:25Z",
            )
        ),
        metadata = JsonObject(emptyMap()),
        checkedAt = "2026-07-22T06:37:18Z",
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-22T06:40:01Z")
    }
}
