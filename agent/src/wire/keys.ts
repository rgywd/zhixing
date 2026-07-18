import { createHmac, randomBytes } from 'node:crypto'
import nacl from 'tweetnacl'

const ROOT_SECRET_SIZE = 32
const DATA_KEY_SIZE = 32
const WRAPPED_KEY_VERSION = 1
const WRAPPED_KEY_MIN_SIZE = 1 + 32 + 24 + 16
const AUTH_DOMAIN = 'Zhixing Wire v1 auth'
const CONTENT_DOMAIN = 'Zhixing Wire v1 content'

export function deriveWireAuthSeed(rootSecret: Uint8Array): Uint8Array {
  return deriveSeed(AUTH_DOMAIN, rootSecret)
}

export function deriveWireContentSecretKey(rootSecret: Uint8Array): Uint8Array {
  return deriveSeed(CONTENT_DOMAIN, rootSecret)
}

export function deriveWireAuthKeyPair(rootSecret: Uint8Array): nacl.SignKeyPair {
  return nacl.sign.keyPair.fromSeed(deriveWireAuthSeed(rootSecret))
}

export function deriveWireContentKeyPair(rootSecret: Uint8Array): nacl.BoxKeyPair {
  return nacl.box.keyPair.fromSecretKey(deriveWireContentSecretKey(rootSecret))
}

export function wrapWireDataKey(
  dataKey: Uint8Array,
  recipientPublicKey: Uint8Array,
  nonce: Uint8Array = randomBytes(nacl.box.nonceLength),
  ephemeralSecretKey?: Uint8Array,
): string {
  assertLength(dataKey, DATA_KEY_SIZE, 'dataKey')
  assertLength(recipientPublicKey, nacl.box.publicKeyLength, 'recipientPublicKey')
  assertLength(nonce, nacl.box.nonceLength, 'nonce')
  const ephemeral = ephemeralSecretKey
    ? nacl.box.keyPair.fromSecretKey(ephemeralSecretKey)
    : nacl.box.keyPair()
  const ciphertext = nacl.box(dataKey, nonce, recipientPublicKey, ephemeral.secretKey)
  return Buffer.concat([
    Buffer.from([WRAPPED_KEY_VERSION]),
    Buffer.from(ephemeral.publicKey),
    Buffer.from(nonce),
    Buffer.from(ciphertext),
  ]).toString('base64url')
}

export function unwrapWireDataKey(bundleBase64Url: string, recipientSecretKey: Uint8Array): Uint8Array | null {
  try {
    assertLength(recipientSecretKey, nacl.box.secretKeyLength, 'recipientSecretKey')
    const bundle = Buffer.from(bundleBase64Url, 'base64url')
    if (bundle.length < WRAPPED_KEY_MIN_SIZE || bundle[0] !== WRAPPED_KEY_VERSION) return null
    const ephemeralPublicKey = bundle.subarray(1, 33)
    const nonce = bundle.subarray(33, 57)
    const ciphertext = bundle.subarray(57)
    return nacl.box.open(ciphertext, nonce, ephemeralPublicKey, recipientSecretKey)
  } catch {
    return null
  }
}

function deriveSeed(domain: string, rootSecret: Uint8Array): Uint8Array {
  assertLength(rootSecret, ROOT_SECRET_SIZE, 'rootSecret')
  return createHmac('sha512', Buffer.from(domain, 'utf8')).update(rootSecret).digest().subarray(0, 32)
}

function assertLength(bytes: Uint8Array, expected: number, name: string): void {
  if (bytes.length !== expected) throw new RangeError(`${name} must be ${expected} bytes`)
}
