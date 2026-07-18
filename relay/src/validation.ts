import { z } from 'zod'

export const opaqueId = z.string().min(1).max(128).regex(/^[A-Za-z0-9_-]+$/)
export const encodedPublicKey = z.string().regex(/^[A-Za-z0-9_-]{43}$/)
export const encodedSignature = z.string().regex(/^[A-Za-z0-9_-]{86}$/)

export const challengeRequest = z.object({ publicKey: encodedPublicKey }).strict()
export const verifyRequest = z
  .object({ challengeId: opaqueId, publicKey: encodedPublicKey, signature: encodedSignature })
  .strict()
export const deviceRequest = z
  .object({ deviceId: opaqueId, publicKey: encodedPublicKey, deviceType: z.enum(['agent', 'android']) })
  .strict()

export const relayEnvelope = z
  .object({
    v: z.literal(1),
    id: opaqueId,
    accountId: opaqueId,
    senderDeviceId: opaqueId,
    targetId: opaqueId,
    streamId: opaqueId,
    seq: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
    createdAt: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
    expiresAt: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).nullable(),
    keyId: opaqueId,
    cipherBundle: z.string().min(39).max(2_800_000).regex(/^[A-Za-z0-9_-]+$/),
  })
  .strict()

export const ackRequest = z
  .object({
    senderDeviceId: opaqueId,
    streamId: opaqueId,
    seq: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
  })
  .strict()
export const tombstoneReceiptRequest = z.object({ envelopeId: opaqueId }).strict()

export function parseBase64Url(value: string, expectedBytes?: number): Buffer {
  const decoded = Buffer.from(value, 'base64url')
  if (expectedBytes !== undefined && decoded.length !== expectedBytes) {
    throw new TypeError(`expected ${expectedBytes} bytes`)
  }
  if (decoded.toString('base64url') !== value) throw new TypeError('non-canonical base64url')
  return decoded
}
