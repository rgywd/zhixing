package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemoryState

@Entity(
    indices = [
        Index(value = ["assistant_id", "kind", "state", "updated_at"]),
    ],
)
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("kind", defaultValue = "'CONTEXT'")
    val kind: String = MemoryKind.CONTEXT.name,
    @ColumnInfo("state", defaultValue = "'ACTIVE'")
    val state: String = MemoryState.ACTIVE.name,
    @ColumnInfo("created_at", defaultValue = "0")
    val createdAt: Long = 0,
    @ColumnInfo("updated_at", defaultValue = "0")
    val updatedAt: Long = 0,
    @ColumnInfo("dimension_id", defaultValue = "''")
    val dimensionId: String = "",
    @ColumnInfo("confidence", defaultValue = "1.0")
    val confidence: Float = 1f,
    @ColumnInfo("source", defaultValue = "'LEGACY'")
    val source: String = "LEGACY",
    @ColumnInfo("evidence_conversation_ids", defaultValue = "'[]'")
    val evidenceConversationIds: String = "[]",
    @ColumnInfo("profile_evidence_json", defaultValue = "'[]'")
    val profileEvidenceJson: String = "[]",
    @ColumnInfo("supporting_observation_ids", defaultValue = "'[]'")
    val supportingObservationIds: String = "[]",
    @ColumnInfo("canonical_key", defaultValue = "''")
    val canonicalKey: String = "",
    @ColumnInfo("first_evidence_at", defaultValue = "0")
    val firstEvidenceAt: Long = 0,
    @ColumnInfo("locked", defaultValue = "0")
    val locked: Boolean = false,
    @ColumnInfo("last_evidence_at", defaultValue = "0")
    val lastEvidenceAt: Long = 0,
)
