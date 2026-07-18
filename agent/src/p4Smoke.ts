import { mkdtemp, rm } from 'node:fs/promises'
import { homedir, tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { AgentHome } from './config.js'
import { publishCatalogOnce } from './wire/catalogPublisher.js'
import { decryptWireBytes } from './wire/crypto.js'
import { deriveWireContentSecretKey, unwrapWireDataKey } from './wire/keys.js'
import { WireRelayAgentClient } from './wire/relayClient.js'
import type { WireEnvelope } from './wire/types.js'

const recoveryKey = required('ZHIXING_SMOKE_RECOVERY_KEY')
const serverUrl = process.env.ZHIXING_WIRE_RELAY_URL ?? 'https://relay.8-208-118-119.sslip.io'

async function main(): Promise<void> {
  const directory = await mkdtemp(join(tmpdir(), 'zhixing-p4-smoke-'))
  const phoneHome = new AgentHome(directory)
  const phone = await WireRelayAgentClient.login(phoneHome, recoveryKey, serverUrl, 'android')
  try {
    const published = await publishCatalogOnce(new AgentHome(join(homedir(), '.zhixing-agent')), true)
    const outbox = await request<{ envelopes: WireEnvelope[] }>(phone, '/v1/outbox?limit=200')
    const keyEnvelopes = outbox.envelopes.filter((envelope) => envelope.streamId === `keys_${phone.deviceId}`)
    const catalogEnvelopes = outbox.envelopes.filter(
      (envelope) => !envelope.streamId.startsWith('keys_') && envelope.streamId.endsWith(`_${phone.deviceId}`),
    )
    if (keyEnvelopes.length === 0 || catalogEnvelopes.length === 0) {
      throw new Error('production outbox did not contain key and catalog envelopes')
    }
    const contentSecret = deriveWireContentSecretKey(Buffer.from(phone.rootSecret, 'base64url'))
    const dataKeys = new Map<string, Uint8Array>()
    for (const envelope of keyEnvelopes) {
      const dataKey = unwrapWireDataKey(envelope.cipherBundle, contentSecret)
      if (!dataKey) throw new Error('production key envelope could not be unwrapped')
      dataKeys.set(envelope.keyId, dataKey)
    }
    const payloads = catalogEnvelopes.map((envelope) => {
      const dataKey = dataKeys.get(envelope.keyId)
      if (!dataKey) throw new Error(`missing production data key ${envelope.keyId}`)
      return JSON.parse(Buffer.from(decryptWireBytes(envelope.cipherBundle, dataKey, envelope)).toString('utf8')) as Payload
    })
    const counts = catalogCounts(payloads)
    if (counts.projects === 0 || counts.threads === 0) throw new Error('catalog snapshot was empty')
    for (const envelope of outbox.envelopes) await acknowledge(phone, envelope)
    console.log(JSON.stringify({
      ok: true,
      relay: phone.serverUrl,
      publishedRecipients: published.recipients,
      projects: counts.projects,
      threads: counts.threads,
      keyEnvelopes: keyEnvelopes.length,
      catalogEnvelopes: catalogEnvelopes.length,
      allEnvelopesAcknowledged: true,
    }))
  } finally {
    await fetch(`${phone.serverUrl}/v1/devices/${phone.deviceId}`, {
      method: 'DELETE',
      headers: headers(phone),
    }).catch(() => undefined)
    await rm(directory, { recursive: true, force: true })
  }
}

function catalogCounts(payloads: Payload[]): { projects: number; threads: number } {
  const direct = payloads.find((payload) => payload.type === 'catalog.snapshot')
  if (direct?.body && isSnapshotBody(direct.body)) {
    return { projects: direct.body.projects.length, threads: direct.body.threads.length }
  }
  const chunks = payloads
    .filter((payload): payload is Payload & { body: ChunkBody } => payload.type === 'catalog.snapshot.chunk' && isChunkBody(payload.body))
    .map((payload) => payload.body)
    .sort((left, right) => left.chunkIndex - right.chunkIndex)
  if (chunks.length === 0 || chunks.length !== chunks[0]!.chunkCount) throw new Error('catalog chunks were incomplete')
  if (chunks.some((chunk, index) => chunk.chunkIndex !== index || chunk.chunkCount !== chunks.length)) {
    throw new Error('catalog chunks were not contiguous')
  }
  const raw = chunks.map((chunk) => {
    const bytes = Buffer.from(chunk.contentBase64, 'base64url')
    if (createHash('sha256').update(bytes).digest('hex') !== chunk.chunkHash) throw new Error('catalog chunk hash mismatch')
    return bytes
  })
  if (createHash('sha256').update(Buffer.concat(raw)).digest('hex') !== chunks[0]!.contentHash) {
    throw new Error('catalog content hash mismatch')
  }
  const contents = raw.map((bytes) => JSON.parse(bytes.toString('utf8')) as SnapshotBody)
  return {
    projects: contents.reduce((count, content) => count + content.projects.length, 0),
    threads: contents.reduce((count, content) => count + content.threads.length, 0),
  }
}

function isSnapshotBody(value: unknown): value is SnapshotBody {
  return !!value && typeof value === 'object' && Array.isArray((value as SnapshotBody).projects) &&
    Array.isArray((value as SnapshotBody).threads)
}

function isChunkBody(value: unknown): value is ChunkBody {
  const body = value as Partial<ChunkBody> | null
  return !!body && Number.isInteger(body.chunkIndex) && Number.isInteger(body.chunkCount) &&
    typeof body.contentBase64 === 'string' && typeof body.contentHash === 'string' && typeof body.chunkHash === 'string'
}

async function acknowledge(credentials: Credentials, envelope: WireEnvelope): Promise<void> {
  await request(credentials, '/v1/acks', {
    method: 'POST',
    body: JSON.stringify({
      senderDeviceId: envelope.senderDeviceId,
      streamId: envelope.streamId,
      seq: envelope.seq,
    }),
  })
}

async function request<T>(credentials: Credentials, path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${credentials.serverUrl}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...headers(credentials), ...(init.headers ?? {}) },
  })
  if (!response.ok) throw new Error(`Relay HTTP ${response.status} ${path}`)
  return await response.json() as T
}

function headers(credentials: Credentials): Record<string, string> {
  return {
    Authorization: `Bearer ${credentials.token}`,
    'X-Zhixing-Device-Id': credentials.deviceId,
  }
}

function required(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`${name} is required`)
  return value
}

interface Credentials {
  serverUrl: string
  deviceId: string
  token: string
  rootSecret: string
}

interface Payload {
  type?: string
  body?: unknown
}

interface SnapshotBody {
  projects: unknown[]
  threads: unknown[]
}

interface ChunkBody {
  chunkIndex: number
  chunkCount: number
  contentHash: string
  chunkHash: string
  contentBase64: string
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error))
  process.exitCode = 1
})
