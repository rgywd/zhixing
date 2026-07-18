import { createPublicKey, timingSafeEqual, verify } from 'node:crypto'
import { RelayError } from './errors.js'
import type { StoredChallenge } from './types.js'

const ED25519_SPKI_PREFIX = Buffer.from('302a300506032b6570032100', 'hex')
const AUTH_DOMAIN = Buffer.from('Zhixing Relay auth v1\n', 'utf8')

export function authChallengeMessage(challenge: StoredChallenge): Buffer {
  const expiresAt = Buffer.alloc(8)
  expiresAt.writeBigUInt64BE(BigInt(challenge.expiresAt))
  return Buffer.concat([
    AUTH_DOMAIN,
    Buffer.from(challenge.challengeId, 'utf8'),
    Buffer.from('\n', 'utf8'),
    challenge.challenge,
    expiresAt,
  ])
}

export function verifyChallengeSignature(challenge: StoredChallenge, publicKey: Buffer, signature: Buffer): void {
  if (!timingSafeEqual(challenge.publicKey, publicKey)) {
    throw new RelayError(401, 'AUTH_FAILED', 'challenge 公钥不匹配')
  }
  if (challenge.usedAt !== null) throw new RelayError(409, 'AUTH_CHALLENGE_REPLAYED', 'challenge 已使用')
  if (challenge.expiresAt < Date.now()) throw new RelayError(401, 'AUTH_CHALLENGE_EXPIRED', 'challenge 已过期')
  const key = createPublicKey({ key: Buffer.concat([ED25519_SPKI_PREFIX, publicKey]), format: 'der', type: 'spki' })
  if (!verify(null, authChallengeMessage(challenge), key, signature)) {
    throw new RelayError(401, 'AUTH_FAILED', '签名无效')
  }
}

export function bearerToken(authorization: string | undefined): string {
  const match = authorization?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)
  if (!match?.[1]) throw new RelayError(401, 'AUTH_FAILED', '缺少 Bearer token')
  return match[1]
}
