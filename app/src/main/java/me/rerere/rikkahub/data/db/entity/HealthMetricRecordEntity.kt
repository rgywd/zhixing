package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "health_metric_records",
    indices = [
        Index(value = ["metric_type", "effective_at_epoch_ms"]),
        Index(value = ["recorded_at_epoch_ms"]),
        Index(value = ["source_message_id"]),
    ],
)
data class HealthMetricRecordEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("metric_type")
    val metricType: String,
    @ColumnInfo("value_decimal")
    val valueDecimal: String,
    val unit: String,
    @ColumnInfo("observed_at_epoch_ms")
    val observedAtEpochMillis: Long? = null,
    @ColumnInfo("recorded_at_epoch_ms")
    val recordedAtEpochMillis: Long,
    @ColumnInfo("effective_at_epoch_ms")
    val effectiveAtEpochMillis: Long,
    @ColumnInfo("source_type")
    val sourceType: String,
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String? = null,
    @ColumnInfo("source_message_id")
    val sourceMessageId: String? = null,
)
