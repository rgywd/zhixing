import type Database from 'better-sqlite3'
import Fastify, { type FastifyRequest } from 'fastify'
import { ZodError, z } from 'zod'
import { bearerToken, verifyChallengeSignature } from './auth.js'
import type { RelayConfig } from './config.js'
import { openRelayDatabase, RELAY_SCHEMA_VERSION } from './database.js'
import { RelayError } from './errors.js'
import { RelayMetrics } from './metrics.js'
import { RelayRepository } from './repository.js'
import { RelaySocketHub } from './socketHub.js'
import type { AuthContext, RelayEnvelope } from './types.js'
import {
  ackRequest,
  challengeRequest,
  deviceRequest,
  opaqueId,
  parseBase64Url,
  relayEnvelope,
  tombstoneReceiptRequest,
  verifyRequest,
} from './validation.js'

export interface RelayServerOptions {
  config: RelayConfig
  database?: Database.Database
  migrationsDirectory?: string
  now?: () => number
}

export function buildRelayServer(options: RelayServerOptions) {
  const ownsDatabase = options.database === undefined
  const database = options.database ?? openRelayDatabase(options.config.databasePath, options.migrationsDirectory)
  const repository = new RelayRepository(database, options.now)
  const metrics = new RelayMetrics()
  const app = Fastify({
    bodyLimit: 3 * 1024 * 1024,
    logger: {
      level: options.config.logLevel,
      redact: ['req.headers.authorization', 'req.headers.x-zhixing-device-id'],
    },
  })
  const socketHub = new RelaySocketHub(app.server, repository, metrics)
  const challengeLimiter = new SlidingWindowLimiter(10, 60_000)
  const cleanup = setInterval(() => repository.cleanup(), 60_000)
  cleanup.unref()

  app.get('/healthz', async () => ({ status: 'ok', wireMajor: 1, schemaVersion: RELAY_SCHEMA_VERSION }))
  app.get('/metrics', async (_request, reply) => {
    reply.type('text/plain; version=0.0.4')
    return metrics.render()
  })

  app.post('/v1/auth/challenges', async (request) => {
    if (!challengeLimiter.allow(request.ip, Date.now())) {
      throw new RelayError(429, 'RATE_LIMITED', 'challenge 请求过于频繁')
    }
    const input = challengeRequest.parse(request.body)
    const challenge = repository.createChallenge(parseBase64Url(input.publicKey, 32), options.config.challengeTtlMs)
    metrics.increment('challenge_created')
    return {
      challengeId: challenge.challengeId,
      challenge: challenge.challenge.toString('base64url'),
      expiresAt: challenge.expiresAt,
      signatureDomain: 'Zhixing Relay auth v1',
    }
  })

  app.post('/v1/auth/verify', async (request) => {
    const input = verifyRequest.parse(request.body)
    const challenge = repository.getChallenge(input.challengeId)
    const publicKey = parseBase64Url(input.publicKey, 32)
    verifyChallengeSignature(challenge, publicKey, parseBase64Url(input.signature, 64))
    repository.consumeChallenge(input.challengeId)
    const issued = repository.issueToken(publicKey, options.config.tokenTtlMs)
    metrics.increment('auth_succeeded')
    return issued
  })

  app.get('/v1/devices', async (request) => {
    const auth = authenticate(request, repository)
    return { devices: repository.listDevices(auth.accountId) }
  })

  app.post('/v1/devices', async (request) => {
    const auth = authenticate(request, repository)
    const input = deviceRequest.parse(request.body)
    const device = repository.registerBoundDevice(auth, {
      deviceId: input.deviceId,
      publicKey: parseBase64Url(input.publicKey, 32),
      deviceType: input.deviceType,
    })
    metrics.increment('device_registered')
    return { device }
  })

  app.delete('/v1/devices/:deviceId', async (request) => {
    const auth = authenticate(request, repository)
    const { deviceId } = z.object({ deviceId: opaqueId }).parse(request.params)
    repository.revokeDevice(auth.accountId, deviceId)
    socketHub.closeDevice(auth.accountId, deviceId)
    metrics.increment('device_revoked')
    return { revoked: true }
  })

  app.post('/v1/envelopes', async (request) => {
    const auth = authenticate(request, repository)
    const envelope = relayEnvelope.parse(request.body) as RelayEnvelope
    if (envelope.accountId !== auth.accountId) throw new RelayError(403, 'AUTH_FAILED', 'accountId 不匹配')
    if (envelope.senderDeviceId !== requireBoundDevice(auth)) {
      throw new RelayError(403, 'DEVICE_TOKEN_MISMATCH', 'senderDeviceId 与 token 不匹配')
    }
    parseBase64Url(envelope.cipherBundle)
    const result = repository.submitEnvelope(envelope)
    socketHub.notifyOutbox(auth.accountId, result.deliveredTo)
    metrics.increment(result.status === 'accepted' ? 'envelope_accepted' : 'envelope_duplicate')
    return result
  })

  app.get('/v1/outbox', async (request) => {
    const auth = authenticate(request, repository)
    const deviceId = requireRequestDevice(request, auth)
    const query = z.object({ limit: z.coerce.number().int().min(1).max(200).default(100) }).parse(request.query)
    return { envelopes: repository.outbox(auth.accountId, deviceId, query.limit) }
  })

  app.post('/v1/acks', async (request) => {
    const auth = authenticate(request, repository)
    const deviceId = requireRequestDevice(request, auth)
    const input = ackRequest.parse(request.body)
    const seq = repository.acknowledge(auth.accountId, deviceId, input)
    metrics.increment('ack_recorded')
    return { seq }
  })

  app.post('/v1/tombstone-receipts', async (request) => {
    const auth = authenticate(request, repository)
    const deviceId = requireRequestDevice(request, auth)
    const input = tombstoneReceiptRequest.parse(request.body)
    repository.recordTombstoneReceipt(auth.accountId, deviceId, input.envelopeId)
    metrics.increment('tombstone_receipt_recorded')
    return { recorded: true }
  })

  app.get('/v1/presence', async (request) => {
    const auth = authenticate(request, repository)
    return {
      devices: repository.listDevices(auth.accountId).map((device) => ({
        deviceId: device.deviceId,
        online: device.revokedAt === null && socketHub.isOnline(auth.accountId, device.deviceId),
        lastSeenAt: device.lastSeenAt,
      })),
    }
  })

  app.setErrorHandler((error, _request, reply) => {
    if (error instanceof RelayError) {
      if (error.status === 401) metrics.increment('auth_failed')
      return reply.code(error.status).send({ error: { code: error.code, message: error.message } })
    }
    if (error instanceof ZodError || error instanceof TypeError) {
      return reply.code(400).send({ error: { code: 'INVALID_REQUEST', message: '请求格式无效' } })
    }
    app.log.error({ error }, 'relay request failed')
    return reply.code(500).send({ error: { code: 'INTERNAL_ERROR', message: '中继内部错误' } })
  })

  app.addHook('onClose', async () => {
    clearInterval(cleanup)
    socketHub.close()
    if (ownsDatabase) database.close()
  })

  return { app, repository, database, socketHub, metrics }
}

function authenticate(request: FastifyRequest, repository: RelayRepository): AuthContext {
  return repository.authenticate(bearerToken(request.headers.authorization))
}

function requireRequestDevice(request: FastifyRequest, auth: AuthContext): string {
  const value = request.headers['x-zhixing-device-id']
  const deviceId = Array.isArray(value) ? value[0] : value
  const parsed = opaqueId.parse(deviceId)
  if (parsed !== requireBoundDevice(auth)) {
    throw new RelayError(403, 'DEVICE_TOKEN_MISMATCH', '设备 header 与 token 不匹配')
  }
  return parsed
}

function requireBoundDevice(auth: AuthContext): string {
  if (!auth.deviceId) throw new RelayError(403, 'DEVICE_TOKEN_UNBOUND', 'token 尚未绑定设备')
  return auth.deviceId
}

class SlidingWindowLimiter {
  private readonly requests = new Map<string, number[]>()

  constructor(
    private readonly limit: number,
    private readonly windowMs: number,
  ) {}

  allow(key: string, now: number): boolean {
    const cutoff = now - this.windowMs
    const recent = (this.requests.get(key) ?? []).filter((timestamp) => timestamp >= cutoff)
    if (recent.length >= this.limit) return false
    recent.push(now)
    this.requests.set(key, recent)
    return true
  }
}
