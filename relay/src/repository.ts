import { createHash, randomBytes, randomUUID } from 'node:crypto'
import type Database from 'better-sqlite3'
import { conflict, forbidden, notFound, RelayError } from './errors.js'
import type {
  AuthContext,
  DeviceRecord,
  RelayEnvelope,
  StoredChallenge,
  SubmitEnvelopeResult,
} from './types.js'

interface EnvelopeRow {
  id: string
  account_id: string
  sender_device_id: string
  target_id: string
  stream_id: string
  seq: number
  created_at: number
  expires_at: number | null
  key_id: string
  cipher_bundle: string
}

export class RelayRepository {
  constructor(
    private readonly database: Database.Database,
    private readonly now: () => number = Date.now,
  ) {}

  createChallenge(publicKey: Buffer, ttlMs: number): StoredChallenge {
    const challenge: StoredChallenge = {
      challengeId: randomUUID().replaceAll('-', '_'),
      publicKey,
      challenge: randomBytes(32),
      expiresAt: this.now() + ttlMs,
      usedAt: null,
    }
    this.database
      .prepare(
        `INSERT INTO auth_challenges(
          challenge_id, auth_public_key, challenge, expires_at, used_at, created_at
        ) VALUES (?, ?, ?, ?, NULL, ?)`,
      )
      .run(challenge.challengeId, challenge.publicKey, challenge.challenge, challenge.expiresAt, this.now())
    return challenge
  }

  getChallenge(challengeId: string): StoredChallenge {
    const row = this.database
      .prepare(
        `SELECT challenge_id, auth_public_key, challenge, expires_at, used_at
         FROM auth_challenges WHERE challenge_id = ?`,
      )
      .get(challengeId) as
      | {
          challenge_id: string
          auth_public_key: Buffer
          challenge: Buffer
          expires_at: number
          used_at: number | null
        }
      | undefined
    if (!row) notFound('AUTH_CHALLENGE_NOT_FOUND', 'challenge 不存在')
    return {
      challengeId: row.challenge_id,
      publicKey: row.auth_public_key,
      challenge: row.challenge,
      expiresAt: row.expires_at,
      usedAt: row.used_at,
    }
  }

  consumeChallenge(challengeId: string): void {
    const result = this.database
      .prepare(
        'UPDATE auth_challenges SET used_at = ? WHERE challenge_id = ? AND used_at IS NULL AND expires_at >= ?',
      )
      .run(this.now(), challengeId, this.now())
    if (result.changes !== 1) conflict('AUTH_CHALLENGE_REPLAYED', 'challenge 已使用或已过期')
  }

  issueToken(publicKey: Buffer, ttlMs: number): { accountId: string; token: string; expiresAt: number } {
    const accountId = accountIdFor(publicKey)
    const token = randomBytes(32).toString('base64url')
    const expiresAt = this.now() + ttlMs
    this.database.transaction(() => {
      this.database
        .prepare('INSERT OR IGNORE INTO accounts(account_id, auth_public_key, created_at) VALUES (?, ?, ?)')
        .run(accountId, publicKey, this.now())
      this.database
        .prepare('INSERT INTO auth_tokens(token_hash, account_id, expires_at, created_at) VALUES (?, ?, ?, ?)')
        .run(tokenHash(token), accountId, expiresAt, this.now())
    })()
    return { accountId, token, expiresAt }
  }

  authenticate(token: string): AuthContext {
    const hash = tokenHash(token)
    const row = this.database
      .prepare(
        `SELECT account_id, device_id FROM auth_tokens
         WHERE token_hash = ? AND revoked_at IS NULL AND expires_at >= ?`,
      )
      .get(hash, this.now()) as { account_id: string; device_id: string | null } | undefined
    if (!row) throw new RelayError(401, 'AUTH_FAILED', 'token 无效或已过期')
    return { accountId: row.account_id, deviceId: row.device_id, tokenHash: hash }
  }

  bindTokenToDevice(auth: AuthContext, deviceId: string): void {
    if (auth.deviceId !== null && auth.deviceId !== deviceId) {
      forbidden('DEVICE_TOKEN_MISMATCH', 'token 已绑定其他设备')
    }
    this.requireActiveDevice(auth.accountId, deviceId)
    const result = this.database
      .prepare(
        `UPDATE auth_tokens SET device_id = ?
         WHERE token_hash = ? AND account_id = ? AND revoked_at IS NULL
           AND (device_id IS NULL OR device_id = ?)`,
      )
      .run(deviceId, auth.tokenHash, auth.accountId, deviceId)
    if (result.changes !== 1) forbidden('DEVICE_TOKEN_MISMATCH', 'token 无法绑定该设备')
  }

  registerDevice(
    accountId: string,
    input: { deviceId: string; publicKey: Buffer; deviceType: 'agent' | 'android' },
  ): DeviceRecord {
    const existing = this.database
      .prepare(
        `SELECT content_public_key, device_type, revoked_at FROM devices
         WHERE account_id = ? AND device_id = ?`,
      )
      .get(accountId, input.deviceId) as
      | { content_public_key: Buffer; device_type: string; revoked_at: number | null }
      | undefined
    if (existing?.revoked_at !== null && existing !== undefined) {
      conflict('DEVICE_REVOKED', '已撤销的 deviceId 不能重新使用')
    }
    if (
      existing &&
      (!existing.content_public_key.equals(input.publicKey) || existing.device_type !== input.deviceType)
    ) {
      conflict('DEVICE_ID_COLLISION', 'deviceId 已绑定不同公钥或类型')
    }
    const seenAt = this.now()
    this.database
      .prepare(
        `INSERT INTO devices(
          account_id, device_id, content_public_key, device_type, last_seen_at, created_at
        ) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(account_id, device_id) DO UPDATE SET last_seen_at = excluded.last_seen_at`,
      )
      .run(accountId, input.deviceId, input.publicKey, input.deviceType, seenAt, seenAt)
    return {
      deviceId: input.deviceId,
      publicKey: input.publicKey.toString('base64url'),
      deviceType: input.deviceType,
      lastSeenAt: seenAt,
      revokedAt: null,
    }
  }

  registerBoundDevice(
    auth: AuthContext,
    input: { deviceId: string; publicKey: Buffer; deviceType: 'agent' | 'android' },
  ): DeviceRecord {
    return this.database.transaction(() => {
      if (auth.deviceId !== null && auth.deviceId !== input.deviceId) {
        forbidden('DEVICE_TOKEN_MISMATCH', 'token 已绑定其他设备')
      }
      const device = this.registerDevice(auth.accountId, input)
      this.bindTokenToDevice(auth, input.deviceId)
      return device
    })()
  }

  listDevices(accountId: string): DeviceRecord[] {
    const rows = this.database
      .prepare(
        `SELECT device_id, content_public_key, device_type, last_seen_at, revoked_at
         FROM devices WHERE account_id = ? ORDER BY created_at`,
      )
      .all(accountId) as Array<{
      device_id: string
      content_public_key: Buffer
      device_type: 'agent' | 'android'
      last_seen_at: number
      revoked_at: number | null
    }>
    return rows.map((row) => ({
      deviceId: row.device_id,
      publicKey: row.content_public_key.toString('base64url'),
      deviceType: row.device_type,
      lastSeenAt: row.last_seen_at,
      revokedAt: row.revoked_at,
    }))
  }

  revokeDevice(accountId: string, deviceId: string): void {
    this.database.transaction(() => {
      const revokedAt = this.now()
      const result = this.database
        .prepare('UPDATE devices SET revoked_at = ? WHERE account_id = ? AND device_id = ? AND revoked_at IS NULL')
        .run(revokedAt, accountId, deviceId)
      if (result.changes !== 1) notFound('DEVICE_NOT_FOUND', '设备不存在或已撤销')
      this.database
        .prepare(
          'UPDATE auth_tokens SET revoked_at = ? WHERE account_id = ? AND device_id = ? AND revoked_at IS NULL',
        )
        .run(revokedAt, accountId, deviceId)
    })()
  }

  requireActiveDevice(accountId: string, deviceId: string): void {
    const row = this.database
      .prepare('SELECT 1 AS present FROM devices WHERE account_id = ? AND device_id = ? AND revoked_at IS NULL')
      .get(accountId, deviceId)
    if (!row) forbidden('DEVICE_REVOKED', '设备不存在或已撤销')
  }

  submitEnvelope(envelope: RelayEnvelope): SubmitEnvelopeResult {
    return this.database.transaction((): SubmitEnvelopeResult => {
      this.requireActiveDevice(envelope.accountId, envelope.senderDeviceId)
      if (envelope.expiresAt !== null && envelope.expiresAt < this.now()) {
        conflict('ENVELOPE_EXPIRED', '过期 envelope 不会入队')
      }
      const byId = this.findEnvelope(envelope.id)
      if (byId) {
        if (!sameEnvelope(byId, envelope)) conflict('ENVELOPE_ID_COLLISION', 'envelope id 对应不同内容')
        return { status: 'duplicate', id: envelope.id, deliveredTo: this.deliveryTargets(envelope.id) }
      }
      const sequence = this.database
        .prepare(
          `SELECT id FROM envelopes
           WHERE account_id = ? AND sender_device_id = ? AND stream_id = ? AND seq = ?`,
        )
        .get(envelope.accountId, envelope.senderDeviceId, envelope.streamId, envelope.seq) as
        | { id: string }
        | undefined
      if (sequence) conflict('SEQUENCE_COLLISION', `seq 已由 ${sequence.id} 使用`)
      const recipients = this.resolveRecipients(envelope)
      this.database
        .prepare(
          `INSERT INTO envelopes(
            id, account_id, sender_device_id, target_id, stream_id, seq, created_at,
            expires_at, key_id, cipher_bundle, received_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        )
        .run(
          envelope.id,
          envelope.accountId,
          envelope.senderDeviceId,
          envelope.targetId,
          envelope.streamId,
          envelope.seq,
          envelope.createdAt,
          envelope.expiresAt,
          envelope.keyId,
          envelope.cipherBundle,
          this.now(),
        )
      const insertDelivery = this.database.prepare(
        'INSERT INTO deliveries(envelope_id, account_id, device_id) VALUES (?, ?, ?)',
      )
      for (const deviceId of recipients) insertDelivery.run(envelope.id, envelope.accountId, deviceId)
      return { status: 'accepted', id: envelope.id, deliveredTo: recipients }
    })()
  }

  outbox(accountId: string, deviceId: string, limit: number): RelayEnvelope[] {
    this.requireActiveDevice(accountId, deviceId)
    const rows = this.database
      .prepare(
        `SELECT e.* FROM deliveries d
         JOIN envelopes e ON e.id = d.envelope_id
         WHERE d.account_id = ? AND d.device_id = ? AND d.acked_at IS NULL
           AND (e.expires_at IS NULL OR e.expires_at >= ?)
         ORDER BY e.row_id LIMIT ?`,
      )
      .all(accountId, deviceId, this.now(), limit) as EnvelopeRow[]
    return rows.map(toEnvelope)
  }

  acknowledge(
    accountId: string,
    receiverDeviceId: string,
    input: { senderDeviceId: string; streamId: string; seq: number },
  ): number {
    this.requireActiveDevice(accountId, receiverDeviceId)
    return this.database.transaction(() => {
      const existing = this.database
        .prepare(
          `SELECT seq FROM stream_acks WHERE account_id = ? AND receiver_device_id = ?
           AND sender_device_id = ? AND stream_id = ?`,
        )
        .get(accountId, receiverDeviceId, input.senderDeviceId, input.streamId) as
        | { seq: number }
        | undefined
      const highest = Math.max(existing?.seq ?? -1, input.seq)
      this.database
        .prepare(
          `INSERT INTO stream_acks(
            account_id, receiver_device_id, sender_device_id, stream_id, seq, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?)
          ON CONFLICT(account_id, receiver_device_id, sender_device_id, stream_id)
          DO UPDATE SET seq = MAX(seq, excluded.seq), updated_at = excluded.updated_at`,
        )
        .run(accountId, receiverDeviceId, input.senderDeviceId, input.streamId, highest, this.now())
      this.database
        .prepare(
          `UPDATE deliveries SET acked_at = ? WHERE account_id = ? AND device_id = ? AND envelope_id IN (
            SELECT id FROM envelopes WHERE account_id = ? AND sender_device_id = ? AND stream_id = ? AND seq <= ?
          )`,
        )
        .run(
          this.now(),
          accountId,
          receiverDeviceId,
          accountId,
          input.senderDeviceId,
          input.streamId,
          highest,
        )
      return highest
    })()
  }

  recordTombstoneReceipt(accountId: string, deviceId: string, envelopeId: string): void {
    this.requireActiveDevice(accountId, deviceId)
    const target = this.database
      .prepare('SELECT 1 AS present FROM deliveries WHERE account_id = ? AND device_id = ? AND envelope_id = ?')
      .get(accountId, deviceId, envelopeId)
    if (!target) notFound('ENVELOPE_NOT_FOUND', '该设备没有收到此 envelope')
    this.database
      .prepare(
        `INSERT INTO tombstone_receipts(account_id, envelope_id, device_id, received_at)
         VALUES (?, ?, ?, ?) ON CONFLICT(envelope_id, device_id) DO NOTHING`,
      )
      .run(accountId, envelopeId, deviceId, this.now())
  }

  touchDevice(accountId: string, deviceId: string): number {
    this.requireActiveDevice(accountId, deviceId)
    const seenAt = this.now()
    this.database
      .prepare('UPDATE devices SET last_seen_at = ? WHERE account_id = ? AND device_id = ?')
      .run(seenAt, accountId, deviceId)
    return seenAt
  }

  cleanup(): void {
    const now = this.now()
    this.database.transaction(() => {
      this.database.prepare('DELETE FROM auth_challenges WHERE expires_at < ?').run(now - 60_000)
      this.database.prepare('DELETE FROM auth_tokens WHERE expires_at < ? OR revoked_at IS NOT NULL').run(now)
      this.database.prepare('DELETE FROM envelopes WHERE expires_at IS NOT NULL AND expires_at < ?').run(now)
    })()
  }

  private findEnvelope(id: string): EnvelopeRow | undefined {
    return this.database.prepare('SELECT * FROM envelopes WHERE id = ?').get(id) as EnvelopeRow | undefined
  }

  private deliveryTargets(id: string): string[] {
    return (
      this.database
        .prepare('SELECT device_id FROM deliveries WHERE envelope_id = ? ORDER BY device_id')
        .all(id) as Array<{ device_id: string }>
    ).map((row) => row.device_id)
  }

  private resolveRecipients(envelope: RelayEnvelope): string[] {
    if (envelope.targetId === envelope.accountId) {
      return (
        this.database
          .prepare(
            `SELECT device_id FROM devices
             WHERE account_id = ? AND revoked_at IS NULL AND device_id <> ? ORDER BY device_id`,
          )
          .all(envelope.accountId, envelope.senderDeviceId) as Array<{ device_id: string }>
      ).map((row) => row.device_id)
    }
    this.requireActiveDevice(envelope.accountId, envelope.targetId)
    return [envelope.targetId]
  }
}

export function accountIdFor(publicKey: Buffer): string {
  return createHash('sha256').update(publicKey).digest('base64url')
}

export function tokenHash(token: string): Buffer {
  return createHash('sha256').update(token, 'utf8').digest()
}

function toEnvelope(row: EnvelopeRow): RelayEnvelope {
  return {
    v: 1,
    id: row.id,
    accountId: row.account_id,
    senderDeviceId: row.sender_device_id,
    targetId: row.target_id,
    streamId: row.stream_id,
    seq: row.seq,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
    keyId: row.key_id,
    cipherBundle: row.cipher_bundle,
  }
}

function sameEnvelope(row: EnvelopeRow, envelope: RelayEnvelope): boolean {
  return (
    row.account_id === envelope.accountId &&
    row.sender_device_id === envelope.senderDeviceId &&
    row.target_id === envelope.targetId &&
    row.stream_id === envelope.streamId &&
    row.seq === envelope.seq &&
    row.created_at === envelope.createdAt &&
    row.expires_at === envelope.expiresAt &&
    row.key_id === envelope.keyId &&
    row.cipher_bundle === envelope.cipherBundle
  )
}
