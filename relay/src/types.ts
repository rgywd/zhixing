export interface AuthContext {
  accountId: string
  deviceId: string | null
  tokenHash: Buffer
}

export interface RelayEnvelope {
  v: 1
  id: string
  accountId: string
  senderDeviceId: string
  targetId: string
  streamId: string
  seq: number
  createdAt: number
  expiresAt: number | null
  keyId: string
  cipherBundle: string
}

export interface DeviceRecord {
  deviceId: string
  publicKey: string
  deviceType: 'agent' | 'android'
  lastSeenAt: number
  revokedAt: number | null
}

export interface StoredChallenge {
  challengeId: string
  publicKey: Buffer
  challenge: Buffer
  expiresAt: number
  usedAt: number | null
}

export type SubmitEnvelopeResult =
  | { status: 'accepted'; id: string; deliveredTo: string[] }
  | { status: 'duplicate'; id: string; deliveredTo: string[] }

export interface PresenceRecord {
  deviceId: string
  online: boolean
  lastSeenAt: number
}
