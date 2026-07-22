package me.rerere.rikkahub.data.quota

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

@Serializable
data class QuotaEnvelope(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("stale_after_seconds") val staleAfterSeconds: Long,
    val items: List<QuotaSnapshot>,
    @SerialName("served_at") val servedAt: String? = null,
    @SerialName("proxy_stale") val proxyStale: Boolean = false,
    @SerialName("proxy_error") val proxyError: QuotaProxyError? = null,
)

@Serializable
data class QuotaSnapshot(
    @SerialName("credential_id") val credentialId: String,
    val provider: String,
    val label: String,
    val state: QuotaState,
    @SerialName("source_status") val sourceStatus: String,
    val plan: String? = null,
    val windows: List<QuotaWindow>,
    val metadata: JsonObject = JsonObject(emptyMap()),
    @SerialName("checked_at") val checkedAt: String,
    val error: QuotaCollectionError? = null,
)

@Serializable
enum class QuotaState {
    @SerialName("ok") OK,
    @SerialName("disabled") DISABLED,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("unsupported") UNSUPPORTED,
    @SerialName("error") ERROR,
}

@Serializable
data class QuotaWindow(
    val key: String,
    val label: String,
    val used: Double? = null,
    val limit: Double? = null,
    val remaining: Double? = null,
    val unit: String,
    @SerialName("used_percent") val usedPercent: Double? = null,
    @SerialName("remaining_percent") val remainingPercent: Double? = null,
    @SerialName("reset_at") val resetAt: String? = null,
    @SerialName("window_seconds") val windowSeconds: Long? = null,
)

@Serializable
data class QuotaCollectionError(
    val code: String,
    val message: String,
    val retriable: Boolean,
)

@Serializable
data class QuotaProxyError(
    val code: String,
    val message: String,
)

internal fun QuotaEnvelope.requireValid(): QuotaEnvelope = apply {
    require(schemaVersion == QUOTA_SCHEMA_VERSION)
    require(staleAfterSeconds >= 0)
    Instant.parse(generatedAt)
    servedAt?.let(Instant::parse)
    items.forEach { snapshot ->
        require(snapshot.credentialId.isNotBlank())
        require(snapshot.provider.isNotBlank())
        require(snapshot.label.isNotBlank())
        Instant.parse(snapshot.checkedAt)
        snapshot.windows.forEach { window ->
            require(window.key.isNotBlank())
            require(window.label.isNotBlank())
            require(window.windowSeconds == null || window.windowSeconds >= 0)
            listOf(window.used, window.limit, window.remaining).filterNotNull().forEach { require(it.isFinite()) }
            listOf(window.usedPercent, window.remainingPercent).filterNotNull().forEach {
                require(it.isFinite() && it in 0.0..100.0)
            }
            window.resetAt?.let(Instant::parse)
        }
    }
}

const val QUOTA_SCHEMA_VERSION = "quota-monitor/v1"
