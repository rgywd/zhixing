import type { WireEnvelope } from './types.js'

export type SequenceDecision =
  | { kind: 'applied'; sequences: number[] }
  | { kind: 'buffered' }
  | { kind: 'duplicate' }
  | { kind: 'gap'; expected: number }
  | { kind: 'expired' }
  | { kind: 'collision' }

export class WireSequenceTracker {
  private readonly buffered = new Map<number, WireEnvelope>()
  private readonly committed = new Map<number, string>()
  private readonly ids = new Map<string, number>()

  constructor(
    private contiguousSeq = 0,
    private readonly maxReorderWindow = 64,
  ) {}

  get checkpoint(): number {
    return this.contiguousSeq
  }

  accept(envelope: WireEnvelope, now: number): SequenceDecision {
    if (envelope.expiresAt !== null && envelope.expiresAt <= now) return { kind: 'expired' }
    const fingerprint = `${envelope.id}:${envelope.cipherBundle}`
    const priorIdSeq = this.ids.get(envelope.id)
    if (priorIdSeq !== undefined && priorIdSeq !== envelope.seq) return { kind: 'collision' }

    const committed = this.committed.get(envelope.seq)
    if (committed !== undefined) return { kind: committed === fingerprint ? 'duplicate' : 'collision' }
    if (envelope.seq <= this.contiguousSeq) return { kind: 'collision' }

    const existing = this.buffered.get(envelope.seq)
    if (existing !== undefined) {
      return { kind: existing.id === envelope.id && existing.cipherBundle === envelope.cipherBundle ? 'duplicate' : 'collision' }
    }
    if (envelope.seq > this.contiguousSeq + this.maxReorderWindow) {
      return { kind: 'gap', expected: this.contiguousSeq + 1 }
    }

    this.buffered.set(envelope.seq, envelope)
    this.ids.set(envelope.id, envelope.seq)
    if (envelope.seq !== this.contiguousSeq + 1) return { kind: 'buffered' }

    const sequences: number[] = []
    while (true) {
      const nextSeq = this.contiguousSeq + 1
      const next = this.buffered.get(nextSeq)
      if (next === undefined) break
      this.buffered.delete(nextSeq)
      this.committed.set(nextSeq, `${next.id}:${next.cipherBundle}`)
      this.contiguousSeq = nextSeq
      sequences.push(nextSeq)
    }
    return { kind: 'applied', sequences }
  }
}

export class WireRevisionGuard {
  private readonly tombstones = new Map<string, number>()

  constructor(private revision = 0) {}

  get currentRevision(): number {
    return this.revision
  }

  applyDelta(baseRevision: number, revision: number): boolean {
    if (baseRevision !== this.revision || revision <= baseRevision) return false
    this.revision = revision
    return true
  }

  applySnapshot(revision: number): boolean {
    if (revision <= this.revision) return false
    this.revision = revision
    return true
  }

  recordTombstone(machineId: string, threadId: string, deletionRevision: number): boolean {
    const key = `${machineId}\u0000${threadId}`
    const current = this.tombstones.get(key)
    if (deletionRevision <= this.revision || (current !== undefined && deletionRevision <= current)) return false
    this.tombstones.set(key, deletionRevision)
    this.revision = Math.max(this.revision, deletionRevision)
    return true
  }

  canUpsert(machineId: string, threadId: string): boolean {
    return !this.tombstones.has(`${machineId}\u0000${threadId}`)
  }
}
