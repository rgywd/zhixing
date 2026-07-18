import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto'
import { buildWireAad } from './aad.js'
import type { WireEnvelopeHeader } from './types.js'
import { WireProtocolError } from './types.js'

export const WIRE_CIPHER_VERSION = 1
export const WIRE_DATA_KEY_SIZE = 32
export const WIRE_NONCE_SIZE = 12

const GCM_TAG_SIZE = 16
const MIN_BUNDLE_SIZE = 1 + WIRE_NONCE_SIZE + GCM_TAG_SIZE

export function encryptWireBytes(
  plaintext: Uint8Array,
  key: Uint8Array,
  header: WireEnvelopeHeader,
  nonce: Uint8Array = randomBytes(WIRE_NONCE_SIZE),
): string {
  assertLength(key, WIRE_DATA_KEY_SIZE, 'key')
  assertLength(nonce, WIRE_NONCE_SIZE, 'nonce')
  const cipher = createCipheriv('aes-256-gcm', key, nonce)
  cipher.setAAD(buildWireAad(header))
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()])
  return Buffer.concat([Buffer.from([WIRE_CIPHER_VERSION]), nonce, ciphertext]).toString('base64url')
}

export function decryptWireBytes(
  cipherBundle: string,
  key: Uint8Array,
  header: WireEnvelopeHeader,
): Uint8Array {
  assertLength(key, WIRE_DATA_KEY_SIZE, 'key')
  try {
    const bundle = Buffer.from(cipherBundle, 'base64url')
    if (bundle.length < MIN_BUNDLE_SIZE || bundle[0] !== WIRE_CIPHER_VERSION) {
      throw new Error('unsupported or truncated cipher bundle')
    }
    const nonce = bundle.subarray(1, 1 + WIRE_NONCE_SIZE)
    const ciphertext = bundle.subarray(1 + WIRE_NONCE_SIZE, bundle.length - GCM_TAG_SIZE)
    const tag = bundle.subarray(bundle.length - GCM_TAG_SIZE)
    const decipher = createDecipheriv('aes-256-gcm', key, nonce)
    decipher.setAAD(buildWireAad(header))
    decipher.setAuthTag(tag)
    return Buffer.concat([decipher.update(ciphertext), decipher.final()])
  } catch (error) {
    if (error instanceof WireProtocolError) throw error
    throw new WireProtocolError('DECRYPTION_FAILED', 'Wire v1 authentication failed')
  }
}

function assertLength(bytes: Uint8Array, expected: number, name: string): void {
  if (bytes.length !== expected) throw new RangeError(`${name} must be ${expected} bytes`)
}
