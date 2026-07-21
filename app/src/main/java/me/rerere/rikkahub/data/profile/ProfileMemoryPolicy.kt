package me.rerere.rikkahub.data.profile

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.ProfileMaintenanceConfig
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.ProfileDimensions
import me.rerere.rikkahub.data.model.ProfileEvidence
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MIN_OBSERVATION_CONTENT_CHARS = 8
private const val MAX_OBSERVATION_CONTENT_CHARS = 300
private const val MIN_EVIDENCE_QUOTE_CHARS = 4
private const val MAX_EVIDENCE_QUOTE_CHARS = 240
private const val MAX_EVIDENCE_PER_CANDIDATE = 20
private const val MIN_PROFILE_SUMMARY_CHARS = 8
private const val MAX_PROFILE_SUMMARY_CHARS = 600
private val sensitiveMaterialPattern = Regex(
    "(?i)(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|bearer\\s+[a-z0-9._-]+|" +
        "password|passwd|密码|身份证|银行卡)",
)

@Serializable
internal data class ProfileObservationResponse(
    val observations: List<ProfileObservationCandidate> = emptyList(),
)

@Serializable
internal data class ProfileObservationCandidate(
    val action: String = "create",
    val targetObservationId: Int? = null,
    val dimensionId: String = "",
    val content: String = "",
    val durable: Boolean = false,
    val sensitive: Boolean = false,
    val evidence: List<ProfileEvidenceReference> = emptyList(),
)

@Serializable
internal data class ProfileEvidenceReference(
    val conversationId: String = "",
    val messageId: String = "",
    val quote: String = "",
)

@Serializable
internal data class ProfileSummaryResponse(
    val summaries: List<ProfileSummaryCandidate> = emptyList(),
)

@Serializable
internal data class ProfileSummaryCandidate(
    val dimensionId: String = "",
    val content: String = "",
    val observationIds: List<Int> = emptyList(),
)

internal data class ProfileEvidenceSource(
    val conversationId: String,
    val messageId: String,
    val text: String,
    val observedAt: Long,
)

internal data class ValidatedProfileObservation(
    val action: String,
    val targetObservationId: Int?,
    val dimensionId: String,
    val content: String,
    val canonicalKey: String,
    val evidence: List<ProfileEvidence>,
)

internal fun validateProfileObservation(
    candidate: ProfileObservationCandidate,
    evidenceSources: Map<String, ProfileEvidenceSource>,
    existingObservationIds: Set<Int>,
): ValidatedProfileObservation? {
    val action = candidate.action.lowercase(Locale.ROOT)
    if (action !in setOf("create", "reinforce", "contradict")) return null
    if (candidate.dimensionId !in ProfileDimensions.builtIn) return null
    if (!candidate.durable || candidate.sensitive) return null

    val targetId = candidate.targetObservationId
    if (action == "create" && targetId != null) return null
    if (action != "create" && targetId !in existingObservationIds) return null

    val content = candidate.content.trim()
    if (content.length !in MIN_OBSERVATION_CONTENT_CHARS..MAX_OBSERVATION_CONTENT_CHARS) return null
    if (sensitiveMaterialPattern.containsMatchIn(content)) return null

    val evidence = candidate.evidence
        .take(MAX_EVIDENCE_PER_CANDIDATE)
        .distinctBy(ProfileEvidenceReference::messageId)
        .mapNotNull { reference ->
            val source = evidenceSources[reference.messageId] ?: return@mapNotNull null
            if (source.conversationId != reference.conversationId) return@mapNotNull null
            val quote = reference.quote.trim()
            if (quote.length !in MIN_EVIDENCE_QUOTE_CHARS..MAX_EVIDENCE_QUOTE_CHARS) {
                return@mapNotNull null
            }
            if (!source.text.contains(quote)) {
                return@mapNotNull null
            }
            if (sensitiveMaterialPattern.containsMatchIn(quote)) return@mapNotNull null
            ProfileEvidence(
                conversationId = source.conversationId,
                messageId = source.messageId,
                quote = quote,
                observedAt = source.observedAt,
            )
        }
    if (evidence.isEmpty() || evidence.size != candidate.evidence.distinctBy { it.messageId }.size) return null

    return ValidatedProfileObservation(
        action = action,
        targetObservationId = targetId,
        dimensionId = candidate.dimensionId,
        content = content,
        canonicalKey = content.normalizedProfileKey(),
        evidence = evidence,
    )
}

internal fun mergeProfileEvidence(
    current: List<ProfileEvidence>,
    incoming: List<ProfileEvidence>,
): List<ProfileEvidence> = (current + incoming)
    .distinctBy(ProfileEvidence::messageId)
    .sortedBy(ProfileEvidence::observedAt)

internal fun observationConfidence(
    evidence: List<ProfileEvidence>,
    config: ProfileMaintenanceConfig,
): Float {
    if (evidence.isEmpty()) return 0f
    val distinctConversations = evidence.map(ProfileEvidence::conversationId).distinct().size
    val distinctDays = evidence.map { TimeUnit.MILLISECONDS.toDays(it.observedAt) }.distinct().size
    val firstAt = evidence.minOf(ProfileEvidence::observedAt)
    val lastAt = evidence.maxOf(ProfileEvidence::observedAt)
    val spanDays = TimeUnit.MILLISECONDS.toDays((lastAt - firstAt).coerceAtLeast(0))

    val conversationScore = (distinctConversations.toFloat() / config.minimumEvidence).coerceIn(0f, 1f)
    val spanScore = (spanDays.toFloat() / config.minimumEvidenceSpanDays).coerceIn(0f, 1f)
    val dayDiversityScore = (distinctDays.toFloat() / config.minimumEvidence).coerceIn(0f, 1f)
    return (conversationScore * 0.35f + spanScore * 0.30f + dayDiversityScore * 0.35f)
        .coerceIn(0f, 1f)
}

internal fun qualifiesForProfile(
    evidence: List<ProfileEvidence>,
    config: ProfileMaintenanceConfig,
): Boolean {
    if (evidence.map(ProfileEvidence::conversationId).distinct().size < config.minimumEvidence) return false
    val firstAt = evidence.minOfOrNull(ProfileEvidence::observedAt) ?: return false
    val lastAt = evidence.maxOfOrNull(ProfileEvidence::observedAt) ?: return false
    val requiredSpan = TimeUnit.DAYS.toMillis(config.minimumEvidenceSpanDays.toLong())
    if (lastAt - firstAt < requiredSpan) return false
    return observationConfidence(evidence, config) >= config.strategy.confidenceThreshold
}

internal fun isObservationStale(
    lastEvidenceAt: Long,
    now: Long,
    staleAfterDays: Int,
): Boolean = lastEvidenceAt > 0 && now - lastEvidenceAt >= TimeUnit.DAYS.toMillis(staleAfterDays.toLong())

internal fun validateProfileSummary(
    candidate: ProfileSummaryCandidate,
    qualifiedObservations: Map<Int, AssistantMemory>,
): ProfileSummaryCandidate? {
    if (candidate.dimensionId !in ProfileDimensions.builtIn) return null
    val content = candidate.content.trim()
    if (content.length !in MIN_PROFILE_SUMMARY_CHARS..MAX_PROFILE_SUMMARY_CHARS) return null
    if (sensitiveMaterialPattern.containsMatchIn(content)) return null
    val observationIds = candidate.observationIds.distinct()
    if (observationIds.isEmpty()) return null
    val observations = observationIds.mapNotNull(qualifiedObservations::get)
    if (observations.size != observationIds.size) return null
    if (observations.any { it.dimensionId != candidate.dimensionId }) return null
    return candidate.copy(content = content, observationIds = observationIds)
}

internal fun String.normalizedProfileKey(): String = trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[\\s。！？!?，,；;：:、]+"), "")
