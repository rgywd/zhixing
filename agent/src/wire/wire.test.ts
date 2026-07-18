import { describe, expect, it } from 'vitest'
import { buildWireAad } from './aad.js'
import { decryptWireBytes, encryptWireBytes } from './crypto.js'
import {
  deriveWireAuthSeed,
  deriveWireContentKeyPair,
  unwrapWireDataKey,
  wrapWireDataKey,
} from './keys.js'
import { WireRevisionGuard, WireSequenceTracker } from './state.js'
import {
  assertWriteCompatible,
  type WireEnvelope,
  type WireEnvelopeHeader,
  validateEnvelope,
  WireProtocolError,
} from './types.js'

const HEADER: WireEnvelopeHeader = {
  v: 1,
  id: 'msg_01',
  accountId: 'acct_test',
  senderDeviceId: 'agent_test',
  targetId: 'account_test',
  streamId: 'thread_test',
  seq: 1,
  createdAt: 1_784_397_723_000,
  expiresAt: null,
  keyId: 'key_test',
}

describe('Wire v1 crypto', () => {
  it('roundtrips bytes and authenticates every routing field', () => {
    const key = new Uint8Array(32).map((_, index) => index)
    const nonce = new Uint8Array(12).map((_, index) => 0xa0 + index)
    const plaintext = Buffer.from('知行 Wire v1', 'utf8')
    const bundle = encryptWireBytes(plaintext, key, HEADER, nonce)

    expect(bundle).toBe('AaChoqOkpaanqKmqqwGH2cXkRyLoCxfi83FLEOWrPpP4AFrAfuDjvCjh0g')
    expect(Buffer.from(decryptWireBytes(bundle, key, HEADER))).toEqual(plaintext)
    for (const changed of [
      { ...HEADER, id: 'msg_02' },
      { ...HEADER, accountId: 'acct_other' },
      { ...HEADER, senderDeviceId: 'agent_other' },
      { ...HEADER, targetId: 'device_other' },
      { ...HEADER, streamId: 'thread_other' },
      { ...HEADER, seq: 2 },
      { ...HEADER, createdAt: HEADER.createdAt + 1 },
      { ...HEADER, expiresAt: HEADER.createdAt + 1000 },
      { ...HEADER, keyId: 'key_other' },
    ]) {
      expect(() => decryptWireBytes(bundle, key, changed)).toThrowError(WireProtocolError)
    }
  })

  it('domain-separates account keys and wraps data keys with bundle v1', () => {
    const rootSecret = new Uint8Array(32).map((_, index) => index)
    const contentKeyPair = deriveWireContentKeyPair(rootSecret)
    expect(deriveWireAuthSeed(rootSecret)).not.toEqual(contentKeyPair.secretKey)
    const dataKey = new Uint8Array(32).map((_, index) => 0x20 + index)
    const nonce = new Uint8Array(24).map((_, index) => index)
    const ephemeralSecret = new Uint8Array(32).map((_, index) => 0x40 + index)
    const wrapped = wrapWireDataKey(dataKey, contentKeyPair.publicKey, nonce, ephemeralSecret)

    expect(wrapped).toBe(
      'AXmmMe7eG_nJjxIDLN6t0OegeTmPx4a4jMhG7ImvhaUaAAECAwQFBgcICQoLDA0ODxAREhMUFRYX3z-ibFpVd4Oi57p4EWTfvGdUtYwIlWzeO_ZDnlLIYq4FhvxyQyl4W-9ncDF8IUcc',
    )
    expect(unwrapWireDataKey(wrapped, contentKeyPair.secretKey)).toEqual(dataKey)
    expect(unwrapWireDataKey(wrapped, new Uint8Array(32).fill(7))).toBeNull()
  })

  it('uses a deterministic binary AAD layout', () => {
    expect(Buffer.from(buildWireAad(HEADER)).subarray(0, 4).toString('utf8')).toBe('ZXW1')
    expect(Buffer.from(buildWireAad(HEADER)).toString('hex')).toBe(
      '5a58573100066d73675f30310009616363745f74657374000a6167656e745f74657374000c6163636f756e745f74657374000b7468726561645f7465737400000000000000010000019f76647578ffffffffffffffff00086b65795f74657374',
    )
  })
})

describe('Wire v1 compatibility and ordering', () => {
  it('blocks writes for a different major or missing capability', () => {
    const hello = {
      wireMajor: 1,
      wireMinor: 0,
      clientKind: 'android' as const,
      clientVersion: '0.2.0',
      deviceId: 'phone_test',
      capabilities: ['catalog.v1'],
    }
    expect(() => assertWriteCompatible(hello, ['catalog.v1'])).not.toThrow()
    expect(() => assertWriteCompatible({ ...hello, wireMajor: 2 }, [])).toThrowError(WireProtocolError)
    expect(() => assertWriteCompatible(hello, ['runtime.v1'])).toThrowError(WireProtocolError)
  })

  it('preserves unknown optional envelope fields', () => {
    const parsed = validateEnvelope({ ...envelope(1, 'msg_01'), futureField: { enabled: true } })
    expect(parsed.futureField).toEqual({ enabled: true })
  })

  it('buffers reorder, releases contiguous messages, and rejects replay collisions', () => {
    const tracker = new WireSequenceTracker(0, 4)
    const second = envelope(2, 'msg_02')
    expect(tracker.accept(second, HEADER.createdAt)).toEqual({ kind: 'buffered' })
    expect(tracker.accept(second, HEADER.createdAt)).toEqual({ kind: 'duplicate' })
    expect(tracker.accept({ ...second, cipherBundle: 'different' }, HEADER.createdAt)).toEqual({ kind: 'collision' })
    expect(tracker.accept(envelope(1, 'msg_01'), HEADER.createdAt)).toEqual({ kind: 'applied', sequences: [1, 2] })
    expect(tracker.accept(envelope(7, 'msg_07'), HEADER.createdAt)).toEqual({ kind: 'gap', expected: 3 })
    expect(tracker.accept({ ...envelope(3, 'msg_03'), expiresAt: HEADER.createdAt }, HEADER.createdAt)).toEqual({ kind: 'expired' })
  })

  it('rejects revision gaps and tombstone resurrection', () => {
    const guard = new WireRevisionGuard(3)
    expect(guard.applyDelta(2, 4)).toBe(false)
    expect(guard.applyDelta(3, 4)).toBe(true)
    expect(guard.applySnapshot(4)).toBe(false)
    expect(guard.recordTombstone('machine_1', 'thread_1', 5)).toBe(true)
    expect(guard.canUpsert('machine_1', 'thread_1')).toBe(false)
    expect(guard.recordTombstone('machine_1', 'thread_1', 4)).toBe(false)
    expect(guard.recordTombstone('machine_1', 'thread_2', 5)).toBe(false)
  })
})

function envelope(seq: number, id: string): WireEnvelope {
  return { ...HEADER, seq, id, cipherBundle: `bundle_${seq}` }
}
