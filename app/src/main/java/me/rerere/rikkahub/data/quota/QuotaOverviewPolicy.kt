package me.rerere.rikkahub.data.quota

import java.time.Instant

enum class ProviderQuotaStatus {
    OK,
    LOW,
    STALE,
    PARTIAL,
    DISABLED,
    UNAVAILABLE,
    UNSUPPORTED,
    ERROR,
    MISSING,
}

data class ProviderQuotaOverview(
    val provider: String,
    val displayName: String,
    val accountCount: Int,
    val usableAccountCount: Int,
    val status: ProviderQuotaStatus,
    val remainingPercent: Double?,
    val windowLabel: String?,
    val resetAt: String?,
)

fun buildQuotaOverviews(
    envelope: QuotaEnvelope?,
    now: Instant = Instant.now(),
): List<ProviderQuotaOverview> {
    val grouped = envelope?.items.orEmpty().groupBy { it.provider.lowercase() }
    val providers = (KNOWN_PROVIDERS + grouped.keys)
        .distinct()
        .sortedWith(compareBy({ KNOWN_PROVIDERS.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }, { it }))
    return providers.map { provider ->
        val snapshots = grouped[provider].orEmpty()
        val okSnapshots = snapshots.filter { it.state == QuotaState.OK }
        val tightest = okSnapshots
            .flatMap { it.windows }
            .filter { it.remainingPercent != null }
            .minByOrNull { it.remainingPercent!! }
        val stale = envelope?.proxyStale == true || snapshots.any { snapshot ->
            runCatching {
                val ageSeconds = now.epochSecond - Instant.parse(snapshot.checkedAt).epochSecond
                ageSeconds > (envelope?.staleAfterSeconds ?: 0)
            }.getOrDefault(true)
        }
        val issueStates = snapshots.map { it.state }.filter { it != QuotaState.OK }.toSet()
        val remaining = tightest?.remainingPercent
        ProviderQuotaOverview(
            provider = provider,
            displayName = providerDisplayName(provider),
            accountCount = snapshots.size,
            usableAccountCount = okSnapshots.size,
            status = when {
                snapshots.isEmpty() -> ProviderQuotaStatus.MISSING
                okSnapshots.isNotEmpty() && issueStates.isNotEmpty() -> ProviderQuotaStatus.PARTIAL
                okSnapshots.isEmpty() -> issueStates.toProviderStatus()
                stale -> ProviderQuotaStatus.STALE
                remaining != null && remaining <= LOW_QUOTA_PERCENT -> ProviderQuotaStatus.LOW
                else -> ProviderQuotaStatus.OK
            },
            remainingPercent = remaining,
            windowLabel = tightest?.label,
            resetAt = tightest?.resetAt,
        )
    }
}

private fun Set<QuotaState>.toProviderStatus(): ProviderQuotaStatus = when {
    QuotaState.ERROR in this -> ProviderQuotaStatus.ERROR
    QuotaState.UNAVAILABLE in this -> ProviderQuotaStatus.UNAVAILABLE
    QuotaState.UNSUPPORTED in this -> ProviderQuotaStatus.UNSUPPORTED
    QuotaState.DISABLED in this -> ProviderQuotaStatus.DISABLED
    else -> ProviderQuotaStatus.ERROR
}

private fun providerDisplayName(provider: String): String = when (provider) {
    "codex" -> "Codex"
    "kimi" -> "Kimi"
    "xai" -> "SuperGrok"
    "anthropic" -> "Anthropic"
    "claude" -> "Claude"
    "antigravity" -> "Antigravity"
    else -> provider.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

private val KNOWN_PROVIDERS = listOf("codex", "kimi", "xai")
private const val LOW_QUOTA_PERCENT = 20.0
