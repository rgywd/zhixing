package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.MemoryKind
import me.rerere.rikkahub.data.model.MemorySource
import me.rerere.rikkahub.data.model.MemoryState
import me.rerere.rikkahub.data.model.ProfileEvidence
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest

internal fun MemoryEntity.asDeletionTombstone(now: Long): MemoryEntity {
    if (state == MemoryState.DELETED.name) return this
    val keepsSuppressionKey =
        kind == MemoryKind.OBSERVATION.name && source == MemorySource.AUTO.name
    return copy(
        content = "",
        state = MemoryState.DELETED.name,
        updatedAt = now,
        confidence = 0f,
        evidenceConversationIds = "[]",
        profileEvidenceJson = "[]",
        supportingObservationIds = "[]",
        canonicalKey = if (keepsSuppressionKey) canonicalKey.memorySuppressionKey() else "",
        firstEvidenceAt = 0,
        locked = true,
        lastEvidenceAt = 0,
    )
}

internal fun deletionTombstones(
    target: MemoryEntity,
    supportingRecords: Collection<MemoryEntity>,
    now: Long,
): List<MemoryEntity> {
    val supportingIds = if (target.kind == MemoryKind.PROFILE.name) {
        target.decodedSupportingObservationIds().toSet()
    } else {
        emptySet()
    }
    val supportingObservations = supportingRecords.filter { record ->
        record.id in supportingIds &&
            record.assistantId == MemoryRepository.GLOBAL_MEMORY_ID &&
            record.kind == MemoryKind.OBSERVATION.name &&
            record.source == MemorySource.AUTO.name
    }
    return (listOf(target) + supportingObservations)
        .distinctBy(MemoryEntity::id)
        .map { it.asDeletionTombstone(now) }
}

internal fun archivedAutomaticProfileSiblings(
    target: MemoryEntity,
    relatedRecords: Collection<MemoryEntity>,
    now: Long,
): List<MemoryEntity> {
    if (target.kind != MemoryKind.PROFILE.name) return emptyList()
    val deletedSupportingIds = target.decodedSupportingObservationIds().toSet()
    if (deletedSupportingIds.isEmpty()) return emptyList()

    return relatedRecords
        .filter { record ->
            record.id != target.id &&
                record.assistantId == MemoryRepository.GLOBAL_MEMORY_ID &&
                record.kind == MemoryKind.PROFILE.name &&
                record.source == MemorySource.AUTO.name &&
                !record.locked &&
                record.state !in setOf(MemoryState.ARCHIVED.name, MemoryState.DELETED.name) &&
                record.decodedSupportingObservationIds().any(deletedSupportingIds::contains)
        }
        .map { record ->
            record.copy(
                state = MemoryState.ARCHIVED.name,
                updatedAt = now,
            )
        }
}

internal fun MemoryEntity.withoutConversationEvidence(
    conversationId: String,
    now: Long,
): MemoryEntity? {
    val currentEvidence = decodedProfileEvidence()
    val remainingEvidence = currentEvidence.filterNot { it.conversationId == conversationId }
    if (remainingEvidence.size == currentEvidence.size) return null

    val shouldArchive =
        source == MemorySource.AUTO.name &&
            !locked &&
            kind in setOf(MemoryKind.PROFILE.name, MemoryKind.OBSERVATION.name)
    return copy(
        state = if (shouldArchive) MemoryState.ARCHIVED.name else state,
        confidence = if (remainingEvidence.isEmpty()) 0f else confidence,
        evidenceConversationIds = JsonInstant.encodeToString(
            remainingEvidence.map(ProfileEvidence::conversationId).distinct()
        ),
        profileEvidenceJson = JsonInstant.encodeToString(remainingEvidence),
        firstEvidenceAt = remainingEvidence.minOfOrNull(ProfileEvidence::observedAt) ?: 0,
        lastEvidenceAt = remainingEvidence.maxOfOrNull(ProfileEvidence::observedAt) ?: 0,
        updatedAt = now,
    )
}

internal fun MemoryEntity.decodedProfileEvidence(): List<ProfileEvidence> =
    runCatching {
        JsonInstant.decodeFromString<List<ProfileEvidence>>(profileEvidenceJson)
    }.getOrDefault(emptyList())

internal fun MemoryEntity.decodedSupportingObservationIds(): List<Int> =
    runCatching {
        JsonInstant.decodeFromString<List<Int>>(supportingObservationIds)
    }.getOrDefault(emptyList())

internal fun String.memorySuppressionKey(): String {
    if (isBlank()) return ""
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    return "sha256:$digest"
}
