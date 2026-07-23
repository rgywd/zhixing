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
    val accounts: List<QuotaAccountOverview>,
    val windowCount: Int,
    val lowWindowCount: Int,
    val remainingPercent: Double?,
)

data class QuotaAccountOverview(
    val credentialId: String,
    val label: String,
    val state: QuotaState,
    val plan: String?,
    val windows: List<QuotaWindowOverview>,
)

data class QuotaWindowOverview(
    val key: String,
    val label: String,
    val groupKey: String?,
    val groupLabel: String?,
    val remainingPercent: Double?,
    val resetAt: String?,
    val windowSeconds: Long?,
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
        val accounts = snapshots.map { snapshot ->
            QuotaAccountOverview(
                credentialId = snapshot.credentialId,
                label = snapshot.label,
                state = snapshot.state,
                plan = snapshot.plan,
                windows = buildWindowOverviews(provider, snapshot.windows),
            )
        }
        val windows = accounts
            .filter { it.state == QuotaState.OK }
            .flatMap { it.windows }
        val tightest = windows
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
            accounts = accounts,
            windowCount = windows.size,
            lowWindowCount = windows.count {
                it.remainingPercent != null && it.remainingPercent <= LOW_QUOTA_PERCENT
            },
            remainingPercent = remaining,
        )
    }
}

internal fun orderQuotaChannels(
    items: List<ProviderQuotaOverview>,
    selectedProvider: String?,
): List<ProviderQuotaOverview> {
    if (selectedProvider == null) return items
    val selected = items.firstOrNull { it.provider == selectedProvider } ?: return items
    return listOf(selected) + items.filterNot { it.provider == selectedProvider }
}

private fun buildWindowOverviews(
    provider: String,
    windows: List<QuotaWindow>,
): List<QuotaWindowOverview> {
    data class IndexedWindow(
        val sourceIndex: Int,
        val overview: QuotaWindowOverview,
    )

    val indexed = windows.mapIndexed { index, window ->
        val group = quotaWindowGroup(provider, window.key)
        IndexedWindow(
            sourceIndex = index,
            overview = QuotaWindowOverview(
                key = window.key,
                label = window.label,
                groupKey = group?.first,
                groupLabel = group?.second,
                remainingPercent = window.remainingPercent,
                resetAt = window.resetAt,
                windowSeconds = window.windowSeconds,
            ),
        )
    }
    val groupOrder = linkedMapOf<String, Int>()
    indexed.forEach { item ->
        item.overview.groupKey?.let { groupOrder.getOrPut(it) { groupOrder.size } }
    }
    return indexed.sortedWith(
        compareBy<IndexedWindow>(
            { item -> item.overview.groupKey?.let { groupOrder[it] } ?: item.sourceIndex },
            { item -> item.overview.windowSeconds ?: Long.MAX_VALUE },
            { item -> item.sourceIndex },
        )
    ).map { it.overview }
}

private fun quotaWindowGroup(provider: String, key: String): Pair<String, String>? {
    if (provider != "codex") return null
    val prefix = key.removeQuotaWindowSuffix()
    val label = when {
        prefix == "code" -> "标准模型"
        prefix == "code-review" -> "代码审查"
        prefix.startsWith("additional-") -> {
            val model = prettyModelName(prefix.removePrefix("additional-"))
            if (model.isBlank()) "额外模型" else "额外 · $model"
        }
        else -> return null
    }
    return prefix to label
}

private fun String.removeQuotaWindowSuffix(): String = when {
    endsWith("-primary") -> removeSuffix("-primary")
    endsWith("-secondary") -> removeSuffix("-secondary")
    else -> this
}

private fun prettyModelName(value: String): String {
    val words = value.split('-').filter { it.isNotBlank() }.map { word ->
        when (word) {
            "gpt" -> "GPT"
            "codex" -> "Codex"
            else -> word.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
        }
    }
    val joined = words.joinToString(" ")
    return GPT_VERSION_PATTERN.replace(joined) { match ->
        "GPT-${match.groupValues[1]}.${match.groupValues[2]}"
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
internal const val LOW_QUOTA_PERCENT = 20.0
private val GPT_VERSION_PATTERN = Regex("""GPT (\d+) (\d+)""")
