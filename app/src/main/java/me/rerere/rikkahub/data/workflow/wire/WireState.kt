package me.rerere.rikkahub.data.workflow.wire

sealed interface SequenceDecision {
    data class Applied(val sequences: List<Long>) : SequenceDecision
    data object Buffered : SequenceDecision
    data object Duplicate : SequenceDecision
    data class Gap(val expected: Long) : SequenceDecision
    data object Expired : SequenceDecision
    data object Collision : SequenceDecision
}

class WireSequenceTracker(
    private var contiguousSeq: Long = 0,
    private val maxReorderWindow: Long = 64,
) {
    private val buffered = mutableMapOf<Long, WireEnvelope>()
    private val committed = mutableMapOf<Long, String>()
    private val ids = mutableMapOf<String, Long>()

    val checkpoint: Long get() = contiguousSeq

    fun accept(envelope: WireEnvelope, now: Long): SequenceDecision {
        val header = envelope.header
        if (header.expiresAt?.let { it <= now } == true) return SequenceDecision.Expired
        val fingerprint = "${header.id}:${envelope.cipherBundle}"
        val priorIdSeq = ids[header.id]
        if (priorIdSeq != null && priorIdSeq != header.seq) return SequenceDecision.Collision

        committed[header.seq]?.let { return if (it == fingerprint) SequenceDecision.Duplicate else SequenceDecision.Collision }
        if (header.seq <= contiguousSeq) return SequenceDecision.Collision
        buffered[header.seq]?.let {
            return if (it.header.id == header.id && it.cipherBundle == envelope.cipherBundle) {
                SequenceDecision.Duplicate
            } else {
                SequenceDecision.Collision
            }
        }
        if (header.seq > contiguousSeq + maxReorderWindow) return SequenceDecision.Gap(contiguousSeq + 1)

        buffered[header.seq] = envelope
        ids[header.id] = header.seq
        if (header.seq != contiguousSeq + 1) return SequenceDecision.Buffered

        val applied = mutableListOf<Long>()
        while (true) {
            val nextSeq = contiguousSeq + 1
            val next = buffered.remove(nextSeq) ?: break
            committed[nextSeq] = "${next.header.id}:${next.cipherBundle}"
            contiguousSeq = nextSeq
            applied += nextSeq
        }
        return SequenceDecision.Applied(applied)
    }
}

class WireRevisionGuard(
    private var revision: Long = 0,
) {
    private val tombstones = mutableMapOf<Pair<String, String>, Long>()

    val currentRevision: Long get() = revision

    fun applyDelta(baseRevision: Long, newRevision: Long): Boolean {
        if (baseRevision != revision || newRevision <= baseRevision) return false
        revision = newRevision
        return true
    }

    fun applySnapshot(newRevision: Long): Boolean {
        if (newRevision <= revision) return false
        revision = newRevision
        return true
    }

    fun recordTombstone(machineId: String, threadId: String, deletionRevision: Long): Boolean {
        val key = machineId to threadId
        val current = tombstones[key]
        if (deletionRevision <= revision || (current != null && deletionRevision <= current)) return false
        tombstones[key] = deletionRevision
        revision = maxOf(revision, deletionRevision)
        return true
    }

    fun canUpsert(machineId: String, threadId: String): Boolean = (machineId to threadId) !in tombstones
}
