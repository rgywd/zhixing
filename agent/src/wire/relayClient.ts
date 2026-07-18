import { randomBytes, randomUUID } from 'node:crypto'
import nacl from 'tweetnacl'
import type { AgentHome, WireAgentCredentials, WireAgentState } from '../config.js'
import { DEFAULT_WIRE_RELAY_URL } from '../config.js'
import { decryptWireBytes, encryptWireBytes } from './crypto.js'
import { deriveWireAuthKeyPair, deriveWireContentKeyPair, unwrapWireDataKey, wrapWireDataKey } from './keys.js'
import type { WireEnvelope, WireEnvelopeHeader } from './types.js'

const AUTH_DOMAIN = Buffer.from('Zhixing Relay auth v1\n', 'utf8')

export interface RelayDevice {
  deviceId: string
  publicKey: string
  deviceType: 'agent' | 'android'
  lastSeenAt: number
  revokedAt: number | null
}

export interface WirePayload {
  type: string
  schema: 1
  requestId: string | null
  sentAt: number
  body: unknown
}

export class WireRelayAgentClient {
  private credentials: WireAgentCredentials
  private state: WireAgentState
  private operationQueue: Promise<void> = Promise.resolve()

  constructor(private readonly home: AgentHome, credentials?: WireAgentCredentials) {
    this.credentials = credentials ?? requiredCredentials(home)
    this.state = home.loadWireState()
  }

  static async login(
    home: AgentHome,
    recoveryKey: string,
    rawServerUrl?: string,
    deviceType: 'agent' | 'android' = 'agent',
  ): Promise<WireAgentCredentials> {
    const serverUrl = normalizeServerUrl(rawServerUrl ?? process.env.ZHIXING_WIRE_RELAY_URL ?? DEFAULT_WIRE_RELAY_URL)
    const rootSecret = decodeRecoveryKey(recoveryKey)
    const auth = deriveWireAuthKeyPair(rootSecret)
    const publicKey = Buffer.from(auth.publicKey).toString('base64url')
    const challenge = await relayRequest<ChallengeResponse>(serverUrl, '/v1/auth/challenges', {
      method: 'POST',
      body: JSON.stringify({ publicKey }),
    })
    const signature = nacl.sign.detached(authMessage(challenge), auth.secretKey)
    const verified = await relayRequest<AuthResponse>(serverUrl, '/v1/auth/verify', {
      method: 'POST',
      body: JSON.stringify({
        challengeId: challenge.challengeId,
        publicKey,
        signature: Buffer.from(signature).toString('base64url'),
      }),
    })
    const settings = home.loadSettings()
    const credentials: WireAgentCredentials = {
      serverUrl,
      accountId: verified.accountId,
      deviceId: settings.machineId,
      token: verified.token,
      tokenExpiresAt: verified.expiresAt,
      rootSecret: Buffer.from(rootSecret).toString('base64url'),
    }
    const content = deriveWireContentKeyPair(rootSecret)
    await relayRequest(serverUrl, '/v1/devices', {
      method: 'POST',
      headers: authHeaders(credentials),
      body: JSON.stringify({
        deviceId: credentials.deviceId,
        publicKey: Buffer.from(content.publicKey).toString('base64url'),
        deviceType,
      }),
    })
    home.saveWireCredentials(credentials)
    return credentials
  }

  async devices(): Promise<RelayDevice[]> {
    const result = await relayRequest<{ devices: RelayDevice[] }>(this.credentials.serverUrl, '/v1/devices', {
      headers: authHeaders(this.credentials),
    })
    return result.devices
  }

  async publish(type: string, body: unknown, streamPrefix: string, requestId: string | null = null): Promise<number> {
    return this.exclusive(async () => {
      await this.flushPending()
      const recipients = (await this.devices()).filter(
        (device) => device.deviceType === 'android' && device.revokedAt === null,
      )
      return this.publishToRecipients(recipients, type, body, streamPrefix, requestId)
    })
  }

  async publishToDevice(
    targetDeviceId: string,
    type: string,
    body: unknown,
    streamPrefix: string,
    requestId: string | null = null,
  ): Promise<number> {
    return this.exclusive(async () => {
      await this.flushPending()
      const recipient = (await this.devices()).find(
        (device) => device.deviceId === targetDeviceId && device.revokedAt === null,
      )
      if (!recipient) throw new Error(`目标设备不可用: ${targetDeviceId}`)
      return this.publishToRecipients([recipient], type, body, streamPrefix, requestId)
    })
  }

  async revokeDevice(deviceId: string): Promise<void> {
    await relayRequest(this.credentials.serverUrl, `/v1/devices/${encodeURIComponent(deviceId)}`, {
      method: 'DELETE',
      headers: authHeaders(this.credentials),
    })
  }

  discardPendingForTarget(deviceId: string): number {
    let discarded = 0
    for (const [streamId, envelope] of Object.entries(this.state.pending)) {
      if (envelope.targetId !== deviceId) continue
      delete this.state.pending[streamId]
      discarded += 1
    }
    if (discarded > 0) this.persist()
    return discarded
  }

  private async publishToRecipients(
    recipients: RelayDevice[],
    type: string,
    body: unknown,
    streamPrefix: string,
    requestId: string | null,
  ): Promise<number> {
    if (recipients.length === 0) return 0
    const dataKey = randomBytes(32)
    const keyId = opaqueUuid()
    const rootSecret = Buffer.from(this.credentials.rootSecret, 'base64url')
    const payload: WirePayload = { type, schema: 1, requestId, sentAt: Date.now(), body }

    for (const recipient of recipients) {
      await this.sendWrappedKey(recipient, keyId, dataKey)
      await this.sendEncryptedPayload(recipient.deviceId, `${streamPrefix}_${recipient.deviceId}`, keyId, dataKey, payload)
    }
    return recipients.length
  }

  async poll(limit = 100): Promise<{ payloads: WirePayload[]; gapDetected: boolean }> {
    return this.exclusive(() => this.pollUnlocked(limit))
  }

  private async pollUnlocked(limit: number): Promise<{ payloads: WirePayload[]; gapDetected: boolean }> {
    const response = await relayRequest<{ envelopes: WireEnvelope[] }>(
      this.credentials.serverUrl,
      `/v1/outbox?limit=${Math.max(1, Math.min(200, limit))}`,
      { headers: authHeaders(this.credentials) },
    )
    const payloads: WirePayload[] = []
    for (const envelope of response.envelopes) {
      const acknowledgementKey = `${envelope.senderDeviceId}|${envelope.streamId}`
      const previous = this.state.acknowledgements[acknowledgementKey]
      if (previous !== undefined && envelope.seq > previous + 1) {
        return { payloads, gapDetected: true }
      }
      if (previous !== undefined && envelope.seq <= previous) {
        await this.acknowledge(envelope)
        continue
      }
      if (envelope.streamId.startsWith('keys_')) {
        const rootSecret = Buffer.from(this.credentials.rootSecret, 'base64url')
        const secretKey = deriveWireContentKeyPair(rootSecret).secretKey
        const dataKey = unwrapWireDataKey(envelope.cipherBundle, secretKey)
        if (!dataKey) throw new Error('无法解封 Android 请求密钥')
        this.state.wrappedKeys[envelope.keyId] = envelope.cipherBundle
      } else {
        const wrappedKey = this.state.wrappedKeys[envelope.keyId]
        if (!wrappedKey) throw new Error(`缺少 Android 请求密钥 ${envelope.keyId}`)
        const rootSecret = Buffer.from(this.credentials.rootSecret, 'base64url')
        const dataKey = unwrapWireDataKey(wrappedKey, deriveWireContentKeyPair(rootSecret).secretKey)
        if (!dataKey) throw new Error('Android 请求密钥已损坏')
        const plaintext = decryptWireBytes(envelope.cipherBundle, dataKey, envelope)
        const payload = JSON.parse(Buffer.from(plaintext).toString('utf8')) as WirePayload
        if (payload.schema !== 1 || typeof payload.type !== 'string') throw new Error('不支持的 Wire 请求载荷')
        payloads.push(payload)
      }
      this.state.acknowledgements[acknowledgementKey] = envelope.seq
      this.persist()
      await this.acknowledge(envelope)
    }
    return { payloads, gapDetected: false }
  }

  requestState(requestId: string): WireAgentState['requests'][string] | undefined {
    return this.state.requests[requestId]
  }

  markRequest(requestId: string, state: 'inflight' | 'completed', result?: unknown, error?: string): void {
    this.state.requests[requestId] = {
      state,
      updatedAt: Date.now(),
      ...(result === undefined ? {} : { result }),
      ...(error === undefined ? {} : { error }),
    }
    const cutoff = Date.now() - 7 * 24 * 60 * 60_000
    for (const [id, request] of Object.entries(this.state.requests)) {
      if (request.updatedAt < cutoff) delete this.state.requests[id]
    }
    this.persist()
  }

  catalogPublishState(): Pick<WireAgentState, 'lastCatalogRevision' | 'lastPublishedAt'> {
    return {
      lastCatalogRevision: this.state.lastCatalogRevision,
      lastPublishedAt: this.state.lastPublishedAt,
    }
  }

  markCatalogPublished(revision: number, publishedAt = Date.now()): void {
    this.state.lastCatalogRevision = revision
    this.state.lastPublishedAt = publishedAt
    this.persist()
  }

  private async flushPending(): Promise<void> {
    for (const [streamId, envelope] of Object.entries(this.state.pending)) {
      await relayRequest(this.credentials.serverUrl, '/v1/envelopes', {
        method: 'POST',
        headers: authHeaders(this.credentials),
        body: JSON.stringify(envelope),
      })
      this.state.sequences[streamId] = envelope.seq
      delete this.state.pending[streamId]
      this.persist()
    }
  }

  private async sendWrappedKey(recipient: RelayDevice, keyId: string, dataKey: Uint8Array): Promise<void> {
    const streamId = `keys_${recipient.deviceId}`
    const header = this.nextHeader(streamId, recipient.deviceId, keyId)
    const envelope: WireEnvelope = {
      ...header,
      cipherBundle: wrapWireDataKey(dataKey, Buffer.from(recipient.publicKey, 'base64url')),
    }
    await this.submit(streamId, envelope)
  }

  private async sendEncryptedPayload(
    targetId: string,
    streamId: string,
    keyId: string,
    dataKey: Uint8Array,
    payload: WirePayload,
  ): Promise<void> {
    const header = this.nextHeader(streamId, targetId, keyId)
    const envelope: WireEnvelope = {
      ...header,
      cipherBundle: encryptWireBytes(Buffer.from(JSON.stringify(payload), 'utf8'), dataKey, header),
    }
    await this.submit(streamId, envelope)
  }

  private nextHeader(streamId: string, targetId: string, keyId: string): WireEnvelopeHeader {
    return {
      v: 1,
      id: opaqueUuid(),
      accountId: this.credentials.accountId,
      senderDeviceId: this.credentials.deviceId,
      targetId,
      streamId,
      seq: (this.state.sequences[streamId] ?? 0) + 1,
      createdAt: Date.now(),
      expiresAt: null,
      keyId,
    }
  }

  private async submit(streamId: string, envelope: WireEnvelope): Promise<void> {
    const pending = this.state.pending[streamId]
    const outgoing = pending ?? envelope
    if (!pending) {
      this.state.pending[streamId] = outgoing
      this.persist()
    }
    await relayRequest(this.credentials.serverUrl, '/v1/envelopes', {
      method: 'POST',
      headers: authHeaders(this.credentials),
      body: JSON.stringify(outgoing),
    })
    this.state.sequences[streamId] = outgoing.seq
    delete this.state.pending[streamId]
    this.persist()
  }

  private async acknowledge(envelope: WireEnvelope): Promise<void> {
    await relayRequest(this.credentials.serverUrl, '/v1/acks', {
      method: 'POST',
      headers: authHeaders(this.credentials),
      body: JSON.stringify({
        senderDeviceId: envelope.senderDeviceId,
        streamId: envelope.streamId,
        seq: envelope.seq,
      }),
    })
  }

  private persist(): void {
    this.home.saveWireState(this.state)
  }

  private async exclusive<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.operationQueue.then(operation, operation)
    this.operationQueue = result.then(() => undefined, () => undefined)
    return result
  }
}

export function decodeRecoveryKey(value: string): Uint8Array {
  const trimmed = value.trim()
  let decoded: Buffer
  try {
    decoded = Buffer.from(trimmed, 'base64url')
    if (decoded.length !== 32 || decoded.toString('base64url') !== trimmed.replace(/=+$/, '')) throw new Error()
  } catch {
    decoded = decodeBase32(trimmed)
  }
  if (decoded.length !== 32) throw new Error('恢复密钥必须是 32 bytes')
  return decoded
}

function decodeBase32(value: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  const normalized = value.toUpperCase().replaceAll('0', 'O').replaceAll('1', 'I')
    .replaceAll('8', 'B').replaceAll('9', 'G').split('').filter((character) => alphabet.includes(character))
  if (normalized.length === 0) throw new Error('恢复密钥格式不正确')
  const output: number[] = []
  let buffer = 0
  let bits = 0
  for (const character of normalized) {
    buffer = (buffer << 5) | alphabet.indexOf(character)
    bits += 5
    if (bits >= 8) {
      bits -= 8
      output.push((buffer >> bits) & 0xff)
    }
  }
  return Buffer.from(output)
}

function authMessage(challenge: ChallengeResponse): Uint8Array {
  const expiresAt = Buffer.alloc(8)
  expiresAt.writeBigUInt64BE(BigInt(challenge.expiresAt))
  return Buffer.concat([
    AUTH_DOMAIN,
    Buffer.from(challenge.challengeId, 'utf8'),
    Buffer.from('\n', 'utf8'),
    Buffer.from(challenge.challenge, 'base64url'),
    expiresAt,
  ])
}

function authHeaders(credentials: WireAgentCredentials): Record<string, string> {
  return {
    Authorization: `Bearer ${credentials.token}`,
    'X-Zhixing-Device-Id': credentials.deviceId,
  }
}

async function relayRequest<T = unknown>(origin: string, path: string, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { ...(init.headers as Record<string, string> | undefined) }
  if (init.body !== undefined && init.body !== null) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${origin}${path}`, {
    ...init,
    headers,
  })
  const text = await response.text()
  if (!response.ok) throw new Error(`Relay HTTP ${response.status} ${path}: ${safeRelayError(text)}`)
  return JSON.parse(text) as T
}

function safeRelayError(text: string): string {
  try {
    const parsed = JSON.parse(text) as { error?: { code?: string; message?: string } }
    return [parsed.error?.code, parsed.error?.message].filter(Boolean).join(': ').slice(0, 200)
  } catch {
    return 'invalid relay response'
  }
}

function normalizeServerUrl(raw: string): string {
  const url = new URL(raw.trim())
  if (url.protocol !== 'https:' && url.hostname !== 'localhost' && url.hostname !== '127.0.0.1') {
    throw new Error('中继地址必须使用 HTTPS')
  }
  if (url.pathname !== '/' || url.search || url.hash) throw new Error('中继地址不能包含路径、查询参数或片段')
  return url.origin
}

function requiredCredentials(home: AgentHome): WireAgentCredentials {
  const credentials = home.loadWireCredentials()
  if (!credentials) throw new Error('尚未登录 Wire Relay：先运行 `zhixing-agent wire-login <恢复密钥>`')
  return credentials
}

function opaqueUuid(): string {
  return randomUUID().replaceAll('-', '_')
}

interface ChallengeResponse {
  challengeId: string
  challenge: string
  expiresAt: number
}

interface AuthResponse {
  accountId: string
  token: string
  expiresAt: number
}
