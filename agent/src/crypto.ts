/**
 * Happy 协议密码学原语。
 *
 * 与 App 侧 HappyCrypto / HappyRecordCrypto / HappySecretKeyCodec 互操作：
 * - 恢复密钥：32 字节 seed，base64url 或分组 base32（含纠错映射 0→O/1→I/8→B/9→G）
 * - 认证：Ed25519（seed 派生密钥对）对 32 字节随机挑战做 detached 签名
 * - 记录加密两个变体：legacy = XSalsa20-Poly1305 secretbox（nonce||cipher），
 *   dataKey = AES-256-GCM（0x00||nonce12||cipher+tag16）
 * - dataKey 解包：版本 0x00||ephemeralPub32||nonce24||box 密文，
 *   接收方私钥 = HMAC-SHA512 派生链（"Happy EnCoder Master Seed" → "content"）→ SHA-512 前 32 字节
 */
import { createHash, createHmac, createCipheriv, createDecipheriv, randomBytes } from 'node:crypto'
import nacl from 'tweetnacl'

export const ACCOUNT_SECRET_SIZE = 32
export const CHALLENGE_SIZE = 32

const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

export type EncryptionVariant = 'legacy' | 'dataKey'

// ---------- 恢复密钥编解码 ----------

export function decodeSecretKey(value: string): Uint8Array {
  const trimmed = value.trim()
  let decoded: Uint8Array
  if (/[-\s]/.test(trimmed) || trimmed.length > 50) {
    decoded = decodeBase32(trimmed)
  } else {
    try {
      decoded = new Uint8Array(Buffer.from(trimmed, 'base64url'))
      if (decoded.length !== ACCOUNT_SECRET_SIZE) decoded = decodeBase32(trimmed)
    } catch {
      decoded = decodeBase32(trimmed)
    }
  }
  if (decoded.length !== ACCOUNT_SECRET_SIZE) {
    throw new Error(`recovery key must decode to ${ACCOUNT_SECRET_SIZE} bytes, got ${decoded.length}`)
  }
  return decoded
}

export function encodeSecretKeyBase64Url(secret: Uint8Array): string {
  assertLength(secret, ACCOUNT_SECRET_SIZE, 'secret')
  return Buffer.from(secret).toString('base64url')
}

function decodeBase32(value: string): Uint8Array {
  const normalized = value
    .toUpperCase()
    .replaceAll('0', 'O')
    .replaceAll('1', 'I')
    .replaceAll('8', 'B')
    .replaceAll('9', 'G')
    .split('')
    .filter((c) => BASE32_ALPHABET.includes(c))
    .join('')
  if (!normalized) throw new Error('recovery key contains no valid characters')
  const output: number[] = []
  let buffer = 0
  let bits = 0
  for (const character of normalized) {
    buffer = (buffer << 5) | BASE32_ALPHABET.indexOf(character)
    bits += 5
    if (bits >= 8) {
      bits -= 8
      output.push((buffer >> bits) & 0xff)
    }
  }
  return new Uint8Array(output)
}

// ---------- 认证 ----------

export interface AuthChallenge {
  challenge: Uint8Array
  signature: Uint8Array
  publicKey: Uint8Array
}

export function createAuthChallenge(
  accountSecret: Uint8Array,
  challenge: Uint8Array = randomBytes(CHALLENGE_SIZE),
): AuthChallenge {
  assertLength(accountSecret, ACCOUNT_SECRET_SIZE, 'accountSecret')
  assertLength(challenge, CHALLENGE_SIZE, 'challenge')
  const keyPair = nacl.sign.keyPair.fromSeed(accountSecret)
  return {
    challenge,
    signature: nacl.sign.detached(challenge, keyPair.secretKey),
    publicKey: keyPair.publicKey,
  }
}

// ---------- 密钥派生与 dataKey 解包 ----------

const CONTENT_KEY_USAGE = 'Happy EnCoder'
const CONTENT_KEY_PATH = 'content'
const WRAPPED_KEY_VERSION = 0
const WRAPPED_KEY_MIN_SIZE = 1 + 32 + 24 + 16

export function deriveContentBoxSecretKey(accountSecret: Uint8Array): Uint8Array {
  assertLength(accountSecret, ACCOUNT_SECRET_SIZE, 'accountSecret')
  const root = hmacSha512(Buffer.from(`${CONTENT_KEY_USAGE} Master Seed`, 'utf8'), accountSecret)
  const child = hmacSha512(
    root.subarray(32, 64),
    Buffer.concat([Buffer.from([0]), Buffer.from(CONTENT_KEY_PATH, 'utf8')]),
  )
  const contentSeed = child.subarray(0, 32)
  return new Uint8Array(createHash('sha512').update(contentSeed).digest().subarray(0, 32))
}

export function unwrapDataKey(encoded: string, accountSecret: Uint8Array): Uint8Array | null {
  try {
    const bundle = new Uint8Array(Buffer.from(encoded, 'base64'))
    if (bundle.length < WRAPPED_KEY_MIN_SIZE || bundle[0] !== WRAPPED_KEY_VERSION) return null
    const ephemeralPublicKey = bundle.subarray(1, 33)
    const nonce = bundle.subarray(33, 57)
    const ciphertext = bundle.subarray(57)
    const contentSecretKey = deriveContentBoxSecretKey(accountSecret)
    return nacl.box.open(ciphertext, nonce, ephemeralPublicKey, contentSecretKey)
  } catch {
    return null
  }
}

/** 用指定 X25519 公钥打包 dataKey（版本 0x00||ephemeralPub||nonce||box），供机器/会话创建时上报 */
export function wrapDataKeyForPublicKey(dataKey: Uint8Array, recipientPublicKey: Uint8Array): string {
  assertLength(dataKey, 32, 'dataKey')
  assertLength(recipientPublicKey, 32, 'recipientPublicKey')
  const ephemeral = nacl.box.keyPair()
  const nonce = randomBytes(24)
  const box = nacl.box(dataKey, nonce, recipientPublicKey, ephemeral.secretKey)
  return Buffer.concat([
    Buffer.from([WRAPPED_KEY_VERSION]),
    Buffer.from(ephemeral.publicKey),
    nonce,
    Buffer.from(box),
  ]).toString('base64')
}

/** 生成新 dataKey 并用账户内容公钥打包，收方用同一账户 secret 可解 */
export function wrapDataKey(dataKey: Uint8Array, accountSecret: Uint8Array): string {
  const recipientSecret = deriveContentBoxSecretKey(accountSecret)
  const recipientPublic = nacl.box.keyPair.fromSecretKey(recipientSecret).publicKey
  return wrapDataKeyForPublicKey(dataKey, recipientPublic)
}

// ---------- 记录加解密 ----------

const AES_VERSION = 0
const AES_KEY_SIZE = 32
const AES_MIN_SIZE = 1 + 12 + 16
const GCM_TAG_LENGTH = 16
const SECRETBOX_NONCE_LENGTH = 24
const SECRETBOX_KEY_LENGTH = 32

export function encryptRecord(value: unknown, key: Uint8Array, variant: EncryptionVariant): string {
  const plaintext = Buffer.from(JSON.stringify(value), 'utf8')
  const bundle = variant === 'dataKey' ? encryptAesGcm(plaintext, key) : encryptSecretBox(plaintext, key)
  return Buffer.from(bundle).toString('base64')
}

export function decryptRecord(encoded: string, key: Uint8Array, variant: EncryptionVariant): unknown | null {
  try {
    const bundle = new Uint8Array(Buffer.from(encoded, 'base64'))
    const plaintext = variant === 'dataKey' ? decryptAesGcm(bundle, key) : decryptSecretBox(bundle, key)
    if (plaintext == null) return null
    return JSON.parse(Buffer.from(plaintext).toString('utf8'))
  } catch {
    return null
  }
}

function encryptAesGcm(plaintext: Uint8Array, key: Uint8Array): Uint8Array {
  assertLength(key, AES_KEY_SIZE, 'key')
  const nonce = randomBytes(12)
  const cipher = createCipheriv('aes-256-gcm', key, nonce)
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()])
  return Buffer.concat([Buffer.from([AES_VERSION]), nonce, encrypted])
}

function decryptAesGcm(bundle: Uint8Array, key: Uint8Array): Uint8Array | null {
  if (key.length !== AES_KEY_SIZE || bundle.length < AES_MIN_SIZE || bundle[0] !== AES_VERSION) return null
  const nonce = bundle.subarray(1, 13)
  const ciphertext = bundle.subarray(13, bundle.length - GCM_TAG_LENGTH)
  const tag = bundle.subarray(bundle.length - GCM_TAG_LENGTH)
  try {
    const decipher = createDecipheriv('aes-256-gcm', key, nonce)
    decipher.setAuthTag(tag)
    return Buffer.concat([decipher.update(ciphertext), decipher.final()])
  } catch {
    return null
  }
}

function encryptSecretBox(plaintext: Uint8Array, key: Uint8Array): Uint8Array {
  assertLength(key, SECRETBOX_KEY_LENGTH, 'key')
  const nonce = randomBytes(SECRETBOX_NONCE_LENGTH)
  const box = nacl.secretbox(plaintext, nonce, key)
  return Buffer.concat([nonce, Buffer.from(box)])
}

function decryptSecretBox(bundle: Uint8Array, key: Uint8Array): Uint8Array | null {
  if (key.length !== SECRETBOX_KEY_LENGTH || bundle.length <= SECRETBOX_NONCE_LENGTH) return null
  const nonce = bundle.subarray(0, SECRETBOX_NONCE_LENGTH)
  const ciphertext = bundle.subarray(SECRETBOX_NONCE_LENGTH)
  return nacl.secretbox.open(ciphertext, nonce, key)
}

// ---------- 工具 ----------

function hmacSha512(key: Uint8Array, data: Uint8Array): Buffer {
  return createHmac('sha512', key).update(data).digest()
}

function assertLength(bytes: Uint8Array, expected: number, name: string): void {
  if (bytes.length !== expected) {
    throw new Error(`${name} must be ${expected} bytes, got ${bytes.length}`)
  }
}
