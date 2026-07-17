import { describe, expect, it } from 'vitest'
import nacl from 'tweetnacl'
import {
  createAuthChallenge,
  decodeSecretKey,
  decryptRecord,
  deriveContentBoxSecretKey,
  encodeSecretKeyBase64Url,
  encryptRecord,
  unwrapDataKey,
  wrapDataKey,
} from './crypto.js'

const SECRET = new Uint8Array(32).map((_, i) => i)

describe('secret key codec', () => {
  it('roundtrips base64url', () => {
    const encoded = encodeSecretKeyBase64Url(SECRET)
    expect(decodeSecretKey(encoded)).toEqual(SECRET)
  })

  it('decodes dashed base32 with error-correcting map', () => {
    // App 侧 formatForBackup 生成分组 base32；0/1/8/9 应被纠正为 O/I/B/G
    const base32 = encodeBase32(SECRET).replace('O', '0')
    const dashed = base32.match(/.{1,5}/g)!.join('-')
    expect(decodeSecretKey(dashed)).toEqual(SECRET)
  })
})

describe('auth challenge', () => {
  it('produces a valid Ed25519 detached signature over the challenge', () => {
    const auth = createAuthChallenge(SECRET)
    expect(auth.challenge).toHaveLength(32)
    expect(auth.publicKey).toEqual(nacl.sign.keyPair.fromSeed(SECRET).publicKey)
    expect(nacl.sign.detached.verify(auth.challenge, auth.signature, auth.publicKey)).toBe(true)
  })
})

describe('record encryption', () => {
  it('roundtrips dataKey variant (AES-256-GCM, version byte 0)', () => {
    const key = new Uint8Array(32).fill(7)
    const encoded = encryptRecord({ hello: '世界' }, key, 'dataKey')
    const bundle = Buffer.from(encoded, 'base64')
    expect(bundle[0]).toBe(0)
    expect(decryptRecord(encoded, key, 'dataKey')).toEqual({ hello: '世界' })
  })

  it('roundtrips legacy variant (secretbox, nonce||cipher)', () => {
    const key = new Uint8Array(32).fill(9)
    const encoded = encryptRecord([1, 2, 3], key, 'legacy')
    expect(decryptRecord(encoded, key, 'legacy')).toEqual([1, 2, 3])
  })

  it('rejects tampered ciphertext', () => {
    const key = new Uint8Array(32).fill(7)
    const bundle = Buffer.from(encryptRecord({ a: 1 }, key, 'dataKey'), 'base64')
    bundle[bundle.length - 1] ^= 0xff
    expect(decryptRecord(bundle.toString('base64'), key, 'dataKey')).toBeNull()
  })
})

describe('data key wrapping', () => {
  it('wrapDataKey output is unwrappable with the same account secret', () => {
    const dataKey = new Uint8Array(32).fill(42)
    const wrapped = wrapDataKey(dataKey, SECRET)
    expect(unwrapDataKey(wrapped, SECRET)).toEqual(dataKey)
  })

  it('unwrap fails with a different account secret', () => {
    const wrapped = wrapDataKey(new Uint8Array(32).fill(42), SECRET)
    const other = new Uint8Array(32).fill(1)
    expect(unwrapDataKey(wrapped, other)).toBeNull()
  })

  it('derivation chain is deterministic', () => {
    expect(Buffer.from(deriveContentBoxSecretKey(SECRET))).toEqual(Buffer.from(deriveContentBoxSecretKey(SECRET)))
  })
})

function encodeBase32(bytes: Uint8Array): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let result = ''
  let buffer = 0
  let bits = 0
  for (const byte of bytes) {
    buffer = (buffer << 8) | byte
    bits += 8
    while (bits >= 5) {
      bits -= 5
      result += alphabet[(buffer >> bits) & 0x1f]
    }
  }
  if (bits > 0) result += alphabet[(buffer << (5 - bits)) & 0x1f]
  return result
}
