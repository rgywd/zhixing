package me.rerere.rikkahub.data.life

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

const val INFORMATION_MONITOR_SCHEMA = "information-monitor/v1"

@Serializable
enum class InformationMonitorChannel {
    @SerialName("email")
    EMAIL,

    @SerialName("feishu")
    FEISHU,
}

@Serializable
enum class InformationMonitorImportance {
    @SerialName("low")
    LOW,

    @SerialName("normal")
    NORMAL,

    @SerialName("high")
    HIGH,

    @SerialName("urgent")
    URGENT,
}

@Serializable
enum class InformationMonitorSourceState {
    @SerialName("ok")
    OK,

    @SerialName("degraded")
    DEGRADED,

    @SerialName("unavailable")
    UNAVAILABLE,

    @SerialName("disabled")
    DISABLED,
}

enum class InformationMonitorFreshness(val value: String) {
    FRESH("fresh"),
    STALE("stale"),
}

data class InformationMonitorSnapshot<T>(
    val data: T,
    val freshness: InformationMonitorFreshness,
)

data class InformationMonitorQuery(
    val channel: InformationMonitorChannel? = null,
    val hours: Int? = null,
    val limit: Int? = null,
    val minImportance: InformationMonitorImportance? = null,
)

@Serializable
data class InformationMonitorSourceStatus(
    val sourceLabel: String,
    val kind: InformationMonitorChannel,
    val state: InformationMonitorSourceState,
    val lastSucceededAt: String? = null,
    val lastErrorCode: String? = null,
    val itemCount24h: Int,
)

@Serializable
data class InformationMonitorStatusEnvelope(
    val schema: String,
    val sources: List<InformationMonitorSourceStatus>,
)

@Serializable
data class InformationMonitorItem(
    val id: String,
    val sourceLabel: String,
    val channel: InformationMonitorChannel,
    val occurredAt: String,
    val sender: String? = null,
    val title: String? = null,
    val summary: String,
    val actionItems: List<String>,
    val importance: InformationMonitorImportance,
)

@Serializable
data class InformationMonitorItemsEnvelope(
    val schema: String,
    val items: List<InformationMonitorItem>,
)

@Serializable
data class InformationMonitorDigestChannel(
    val channel: InformationMonitorChannel,
    val count: Int,
    val topItems: List<InformationMonitorItem>,
)

@Serializable
data class InformationMonitorDigestEnvelope(
    val schema: String,
    val total: Int,
    val highPriority: Int,
    val channels: List<InformationMonitorDigestChannel>,
)

fun InformationMonitorStatusEnvelope.requireValid(): InformationMonitorStatusEnvelope = apply {
    require(schema == INFORMATION_MONITOR_SCHEMA) { "unsupported information monitor schema" }
    require(sources.size <= MAX_SOURCES) { "too many information monitor sources" }
    sources.forEach { source ->
        requireSafeText(source.sourceLabel, "sourceLabel", MAX_LABEL_LENGTH)
        require(source.itemCount24h >= 0) { "itemCount24h must be non-negative" }
        source.lastSucceededAt?.let(::requireUtcInstant)
        source.lastErrorCode?.let { requireSafeText(it, "lastErrorCode", MAX_ERROR_CODE_LENGTH) }
    }
}

fun InformationMonitorItemsEnvelope.requireValid(): InformationMonitorItemsEnvelope = apply {
    require(schema == INFORMATION_MONITOR_SCHEMA) { "unsupported information monitor schema" }
    require(items.size <= MAX_ITEMS) { "too many information monitor items" }
    items.forEach(InformationMonitorItem::requireValid)
}

fun InformationMonitorDigestEnvelope.requireValid(): InformationMonitorDigestEnvelope = apply {
    require(schema == INFORMATION_MONITOR_SCHEMA) { "unsupported information monitor schema" }
    require(total >= 0) { "total must be non-negative" }
    require(highPriority in 0..total) { "highPriority must be between zero and total" }
    require(channels.size <= InformationMonitorChannel.entries.size) {
        "too many information monitor digest channels"
    }
    require(channels.map { it.channel }.distinct().size == channels.size) {
        "digest channels must be unique"
    }
    require(channels.sumOf { it.count.toLong() } == total.toLong()) {
        "digest channel counts must equal total"
    }
    channels.forEach { channel ->
        require(channel.count >= 0) { "channel count must be non-negative" }
        require(channel.topItems.size <= MAX_ITEMS) { "too many digest top items" }
        require(channel.topItems.size <= channel.count) { "digest top items exceed channel count" }
        channel.topItems.forEach { item ->
            item.requireValid()
            require(item.channel == channel.channel) { "digest item channel does not match its group" }
        }
    }
}

private fun InformationMonitorItem.requireValid() {
    requireSafeText(id, "id", MAX_ID_LENGTH)
    requireSafeText(sourceLabel, "sourceLabel", MAX_LABEL_LENGTH)
    requireUtcInstant(occurredAt)
    sender?.let { requireSafeText(it, "sender", MAX_SENDER_LENGTH) }
    title?.let { requireSafeText(it, "title", MAX_TITLE_LENGTH) }
    requireSafeText(summary, "summary", MAX_SUMMARY_LENGTH)
    require(actionItems.size <= MAX_ACTION_ITEMS) { "too many action items" }
    actionItems.forEach { requireSafeText(it, "actionItems", MAX_ACTION_ITEM_LENGTH) }
}

private fun requireUtcInstant(value: String) {
    require(value.endsWith("Z")) { "timestamp must use UTC RFC3339 format" }
    Instant.parse(value)
}

private fun requireSafeText(value: String, field: String, maxLength: Int) {
    require(value.isNotBlank()) { "$field must not be blank" }
    require(value.length <= maxLength) { "$field is too long" }
    require(value.none { it == '\u0000' }) { "$field contains an invalid character" }
}

private const val MAX_SOURCES = 100
private const val MAX_ITEMS = 50
private const val MAX_ID_LENGTH = 200
private const val MAX_LABEL_LENGTH = 120
private const val MAX_ERROR_CODE_LENGTH = 120
private const val MAX_SENDER_LENGTH = 500
private const val MAX_TITLE_LENGTH = 500
private const val MAX_SUMMARY_LENGTH = 4_000
private const val MAX_ACTION_ITEMS = 20
private const val MAX_ACTION_ITEM_LENGTH = 1_000
