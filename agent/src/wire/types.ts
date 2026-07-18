export const WIRE_MAJOR = 1
export const WIRE_MINOR = 0

const OPAQUE_ID = /^[A-Za-z0-9_-]+$/

export type WireErrorCode =
  | 'WIRE_MAJOR_UNSUPPORTED'
  | 'CAPABILITY_UNSUPPORTED'
  | 'DECRYPTION_FAILED'
  | 'REPLAY_REJECTED'
  | 'REVISION_CONFLICT'
  | 'REQUEST_EXPIRED'

export class WireProtocolError extends Error {
  constructor(
    readonly code: WireErrorCode,
    message: string,
  ) {
    super(message)
    this.name = 'WireProtocolError'
  }
}

export interface WireHello {
  wireMajor: number
  wireMinor: number
  clientKind: 'android' | 'agent' | 'relay'
  clientVersion: string
  deviceId: string
  capabilities: string[]
  [key: string]: unknown
}

export interface WireEnvelopeHeader {
  v: number
  id: string
  accountId: string
  senderDeviceId: string
  targetId: string
  streamId: string
  seq: number
  createdAt: number
  expiresAt: number | null
  keyId: string
  [key: string]: unknown
}

export interface WireEnvelope extends WireEnvelopeHeader {
  cipherBundle: string
}

export function assertWriteCompatible(hello: WireHello, requiredCapabilities: readonly string[]): void {
  if (hello.wireMajor !== WIRE_MAJOR) {
    throw new WireProtocolError(
      'WIRE_MAJOR_UNSUPPORTED',
      `wire major ${hello.wireMajor} is not writable by ${WIRE_MAJOR}.${WIRE_MINOR}`,
    )
  }
  const missing = requiredCapabilities.filter((capability) => !hello.capabilities.includes(capability))
  if (missing.length > 0) {
    throw new WireProtocolError('CAPABILITY_UNSUPPORTED', `missing capabilities: ${missing.join(', ')}`)
  }
}

export function validateEnvelopeHeader(value: unknown): WireEnvelopeHeader {
  if (!isRecord(value)) throw new TypeError('envelope header must be an object')

  const header: WireEnvelopeHeader = {
    ...value,
    v: requireSafeInteger(value.v, 'v', 0),
    id: requireOpaqueId(value.id, 'id'),
    accountId: requireOpaqueId(value.accountId, 'accountId'),
    senderDeviceId: requireOpaqueId(value.senderDeviceId, 'senderDeviceId'),
    targetId: requireOpaqueId(value.targetId, 'targetId'),
    streamId: requireOpaqueId(value.streamId, 'streamId'),
    seq: requireSafeInteger(value.seq, 'seq', 0),
    createdAt: requireSafeInteger(value.createdAt, 'createdAt', 0),
    expiresAt:
      value.expiresAt == null ? null : requireSafeInteger(value.expiresAt, 'expiresAt', 0),
    keyId: requireOpaqueId(value.keyId, 'keyId'),
  }
  return header
}

export function validateEnvelope(value: unknown): WireEnvelope {
  const header = validateEnvelopeHeader(value)
  if (!isRecord(value) || typeof value.cipherBundle !== 'string' || !isBase64Url(value.cipherBundle)) {
    throw new TypeError('cipherBundle must be unpadded base64url')
  }
  return { ...value, ...header, cipherBundle: value.cipherBundle }
}

function requireOpaqueId(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0 || !OPAQUE_ID.test(value)) {
    throw new TypeError(`${field} must match ${OPAQUE_ID}`)
  }
  return value
}

function requireSafeInteger(value: unknown, field: string, minimum: number): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) {
    throw new TypeError(`${field} must be a safe integer >= ${minimum}`)
  }
  return value
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isBase64Url(value: string): boolean {
  return value.length > 0 && /^[A-Za-z0-9_-]+$/.test(value)
}
