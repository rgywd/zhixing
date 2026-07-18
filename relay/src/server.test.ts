import { generateKeyPairSync, randomBytes, sign } from 'node:crypto'
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import type { FastifyInstance } from 'fastify'
import { afterEach, describe, expect, it } from 'vitest'
import { WebSocket } from 'ws'
import { authChallengeMessage } from './auth.js'
import type { RelayConfig } from './config.js'
import { buildRelayServer } from './server.js'
import type { RelayEnvelope, StoredChallenge } from './types.js'

const temporaryDirectories: string[] = []
const openApps: FastifyInstance[] = []

afterEach(async () => {
  await Promise.all(openApps.splice(0).map((app) => app.close()))
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })),
  )
})

describe('Zhixing Relay v1', () => {
  it('authenticates once, persists opaque outbox, deduplicates, acks, and survives restart', async () => {
    const fixture = await serverFixture()
    const { agent: identity, phone } = await authenticateDevices(fixture.app)

    const first = envelope(identity.accountId, 'envelope_1', 1)
    const accepted = await postEnvelope(fixture.app, identity.token, first)
    expect(accepted.statusCode).toBe(200)
    expect(accepted.json()).toEqual({ status: 'accepted', id: first.id, deliveredTo: ['phone_1'] })

    const duplicate = await postEnvelope(fixture.app, identity.token, first)
    expect(duplicate.json()).toMatchObject({ status: 'duplicate', id: first.id })
    const collision = await postEnvelope(fixture.app, identity.token, { ...first, id: 'envelope_collision' })
    expect(collision.statusCode).toBe(409)
    expect(collision.json()).toMatchObject({ error: { code: 'SEQUENCE_COLLISION' } })

    const outbox = await fetchOutbox(fixture.app, phone.token, 'phone_1')
    expect(outbox.json().envelopes).toEqual([first])
    const ack = await fixture.app.inject({
      method: 'POST',
      url: '/v1/acks',
      headers: authHeaders(phone.token, 'phone_1'),
      payload: { senderDeviceId: 'agent_1', streamId: 'catalog_1', seq: 1 },
    })
    expect(ack.json()).toEqual({ seq: 1 })
    expect((await fetchOutbox(fixture.app, phone.token, 'phone_1')).json().envelopes).toEqual([])

    const second = envelope(identity.accountId, 'envelope_2', 2)
    await postEnvelope(fixture.app, identity.token, second)
    await fixture.app.close()
    openApps.splice(openApps.indexOf(fixture.app), 1)

    const restarted = buildRelayServer({ config: fixture.config })
    openApps.push(restarted.app)
    expect((await fetchOutbox(restarted.app, phone.token, 'phone_1')).json().envelopes).toEqual([second])
    const receipt = await restarted.app.inject({
      method: 'POST',
      url: '/v1/tombstone-receipts',
      headers: authHeaders(phone.token, 'phone_1'),
      payload: { envelopeId: second.id },
    })
    expect(receipt.json()).toEqual({ recorded: true })
    const presence = await restarted.app.inject({
      method: 'GET',
      url: '/v1/presence',
      headers: authHeaders(identity.token),
    })
    expect(presence.json().devices).toEqual(
      expect.arrayContaining([expect.objectContaining({ deviceId: 'phone_1', online: false })]),
    )
  })

  it('rejects challenge replay, account mismatch, revoked devices, and plaintext fields', async () => {
    const fixture = await serverFixture()
    const keyPair = generateKeyPairSync('ed25519')
    const publicKey = rawPublicKey(keyPair.publicKey.export({ format: 'der', type: 'spki' }))
    const challengeResponse = await requestChallenge(fixture.app, publicKey)
    const verification = signedVerification(keyPair.privateKey, publicKey, challengeResponse)
    const first = await fixture.app.inject({ method: 'POST', url: '/v1/auth/verify', payload: verification })
    expect(first.statusCode).toBe(200)
    const replay = await fixture.app.inject({ method: 'POST', url: '/v1/auth/verify', payload: verification })
    expect(replay.statusCode).toBe(409)
    expect(replay.json()).toMatchObject({ error: { code: 'AUTH_CHALLENGE_REPLAYED' } })

    const identity = first.json() as AuthIdentity
    const phone = await authenticateWithKey(fixture.app, keyPair)
    await registerDevice(fixture.app, identity, 'agent_1', 'agent')
    await registerDevice(fixture.app, phone, 'phone_1', 'android')
    const impersonation = await fetchOutbox(fixture.app, identity.token, 'phone_1')
    expect(impersonation.statusCode).toBe(403)
    expect(impersonation.json()).toMatchObject({ error: { code: 'DEVICE_TOKEN_MISMATCH' } })
    const mismatch = await postEnvelope(fixture.app, identity.token, {
      ...envelope(identity.accountId, 'mismatch_1', 1),
      accountId: 'different_account',
    })
    expect(mismatch.statusCode).toBe(403)

    const marker = 'C_USERS_SECRET_PROJECT_TITLE_DO_NOT_STORE'
    const plaintext = await fixture.app.inject({
      method: 'POST',
      url: '/v1/envelopes',
      headers: authHeaders(identity.token),
      payload: { ...envelope(identity.accountId, 'plaintext_1', 1), plaintext: marker },
    })
    expect(plaintext.statusCode).toBe(400)

    await fixture.app.inject({
      method: 'DELETE',
      url: '/v1/devices/phone_1',
      headers: authHeaders(identity.token),
    })
    expect((await fetchOutbox(fixture.app, phone.token, 'phone_1')).statusCode).toBe(401)
    await fixture.app.close()
    openApps.splice(openApps.indexOf(fixture.app), 1)
    const persisted = await Promise.all(
      (await readdir(fixture.config.dataDir)).map((name) => readFile(path.join(fixture.config.dataDir, name))),
    )
    expect(persisted.some((bytes) => bytes.includes(Buffer.from(marker)))).toBe(false)
  })

  it('notifies a connected target without transporting ciphertext over websocket', async () => {
    const fixture = await serverFixture()
    const { agent: identity, phone } = await authenticateDevices(fixture.app)
    await fixture.app.listen({ host: '127.0.0.1', port: 0 })
    const address = fixture.app.server.address()
    if (!address || typeof address === 'string') throw new Error('missing listen address')
    const socket = new WebSocket(`ws://127.0.0.1:${address.port}/v1/socket`, {
      headers: authHeaders(phone.token, 'phone_1'),
    })
    const messages: unknown[] = []
    socket.on('message', (data) => messages.push(JSON.parse(data.toString())))
    await waitFor(() => messages.some((message) => (message as { type?: string }).type === 'ready'))

    await postEnvelope(fixture.app, identity.token, envelope(identity.accountId, 'socket_1', 1))
    await waitFor(() => messages.some((message) => (message as { type?: string }).type === 'outbox.available'))
    expect(JSON.stringify(messages)).not.toContain('cipherBundle')
    socket.close()
  })

  it('persists direct RPC envelopes that arrive out of order until the receiver ACKs the gap', async () => {
    const fixture = await serverFixture()
    const { agent, phone } = await authenticateDevices(fixture.app)
    const rpc = (id: string, seq: number): RelayEnvelope => ({
      ...envelope(agent.accountId, id, seq),
      senderDeviceId: 'phone_1',
      targetId: 'agent_1',
      streamId: 'rpc_control',
    })
    expect((await postEnvelope(fixture.app, phone.token, rpc('rpc_2', 2))).statusCode).toBe(200)
    expect((await postEnvelope(fixture.app, phone.token, rpc('rpc_1', 1))).statusCode).toBe(200)
    const pending = (await fetchOutbox(fixture.app, agent.token, 'agent_1')).json().envelopes as RelayEnvelope[]
    expect(pending.map((item) => item.seq)).toEqual([2, 1])
    await fixture.app.inject({
      method: 'POST',
      url: '/v1/acks',
      headers: authHeaders(agent.token, 'agent_1'),
      payload: { senderDeviceId: 'phone_1', streamId: 'rpc_control', seq: 2 },
    })
    expect((await fetchOutbox(fixture.app, agent.token, 'agent_1')).json().envelopes).toEqual([])
  })
})

interface AuthIdentity {
  accountId: string
  token: string
  expiresAt: number
}

async function serverFixture() {
  const directory = await mkdtemp(path.join(tmpdir(), 'zhixing-relay-test-'))
  temporaryDirectories.push(directory)
  const config: RelayConfig = {
    host: '127.0.0.1',
    port: 3100,
    dataDir: directory,
    databasePath: path.join(directory, 'relay.sqlite3'),
    tokenTtlMs: 60_000,
    challengeTtlMs: 60_000,
    logLevel: 'silent',
  }
  const server = buildRelayServer({ config })
  openApps.push(server.app)
  return { ...server, config }
}

async function authenticateWithKey(
  app: FastifyInstance,
  keyPair: ReturnType<typeof generateKeyPairSync>,
): Promise<AuthIdentity> {
  const publicKey = rawPublicKey(keyPair.publicKey.export({ format: 'der', type: 'spki' }))
  const challenge = await requestChallenge(app, publicKey)
  const response = await app.inject({
    method: 'POST',
    url: '/v1/auth/verify',
    payload: signedVerification(keyPair.privateKey, publicKey, challenge),
  })
  expect(response.statusCode).toBe(200)
  return response.json() as AuthIdentity
}

async function authenticateDevices(app: FastifyInstance): Promise<{ agent: AuthIdentity; phone: AuthIdentity }> {
  const keyPair = generateKeyPairSync('ed25519')
  const agent = await authenticateWithKey(app, keyPair)
  const phone = await authenticateWithKey(app, keyPair)
  await registerDevice(app, agent, 'agent_1', 'agent')
  await registerDevice(app, phone, 'phone_1', 'android')
  return { agent, phone }
}

async function requestChallenge(app: FastifyInstance, publicKey: Buffer) {
  const response = await app.inject({
    method: 'POST',
    url: '/v1/auth/challenges',
    payload: { publicKey: publicKey.toString('base64url') },
  })
  expect(response.statusCode).toBe(200)
  return response.json() as { challengeId: string; challenge: string; expiresAt: number }
}

function signedVerification(
  privateKey: ReturnType<typeof generateKeyPairSync>['privateKey'],
  publicKey: Buffer,
  challenge: { challengeId: string; challenge: string; expiresAt: number },
) {
  const stored: StoredChallenge = {
    challengeId: challenge.challengeId,
    publicKey,
    challenge: Buffer.from(challenge.challenge, 'base64url'),
    expiresAt: challenge.expiresAt,
    usedAt: null,
  }
  return {
    challengeId: challenge.challengeId,
    publicKey: publicKey.toString('base64url'),
    signature: sign(null, authChallengeMessage(stored), privateKey).toString('base64url'),
  }
}

async function registerDevice(
  app: FastifyInstance,
  identity: AuthIdentity,
  deviceId: string,
  deviceType: 'agent' | 'android',
) {
  const response = await app.inject({
    method: 'POST',
    url: '/v1/devices',
    headers: authHeaders(identity.token),
    payload: { deviceId, publicKey: randomBytes(32).toString('base64url'), deviceType },
  })
  expect(response.statusCode).toBe(200)
}

function envelope(accountId: string, id: string, seq: number): RelayEnvelope {
  return {
    v: 1,
    id,
    accountId,
    senderDeviceId: 'agent_1',
    targetId: accountId,
    streamId: 'catalog_1',
    seq,
    createdAt: 1_784_400_000_000 + seq,
    expiresAt: null,
    keyId: 'key_1',
    cipherBundle: Buffer.concat([Buffer.from([1]), randomBytes(64)]).toString('base64url'),
  }
}

async function postEnvelope(app: FastifyInstance, token: string, payload: unknown) {
  return await app.inject({
    method: 'POST',
    url: '/v1/envelopes',
    headers: authHeaders(token),
    payload: payload as Record<string, unknown>,
  })
}

async function fetchOutbox(app: FastifyInstance, token: string, deviceId: string) {
  return await app.inject({ method: 'GET', url: '/v1/outbox', headers: authHeaders(token, deviceId) })
}

function authHeaders(token: string, deviceId?: string): Record<string, string> {
  return {
    authorization: `Bearer ${token}`,
    ...(deviceId ? { 'x-zhixing-device-id': deviceId } : {}),
  }
}

function rawPublicKey(der: string | Buffer): Buffer {
  return Buffer.from(der).subarray(-32)
}

async function waitFor(predicate: () => boolean, timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error('condition timed out')
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
}
