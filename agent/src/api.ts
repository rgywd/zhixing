/**
 * Happy 协议 HTTP 层：认证、机器注册、会话创建、v3 消息收发。
 * 端点与载荷以 happy monorepo docs/api.md 及 CLI api.ts 为准。
 */
import { randomBytes, randomUUID } from 'node:crypto'
import {
  createAuthChallenge,
  decodeSecretKey,
  decryptRecord,
  deriveContentBoxSecretKey,
  encodeSecretKeyBase64Url,
  encryptRecord,
  wrapDataKeyForPublicKey,
} from './crypto.js'
import nacl from 'tweetnacl'
import type { Credentials, MachineIdentity, MachineMetadata, SessionHandle } from './types.js'

export class HttpApiError extends Error {
  constructor(
    readonly status: number,
    readonly path: string,
    detail: string,
  ) {
    super(`HTTP ${status} on ${path}: ${detail}`)
  }
}

export interface EncryptedMessageRecord {
  id: string
  seq: number
  content: unknown
  localId?: string
}

export class HappyApi {
  constructor(
    private readonly serverUrl: string,
    private readonly clientId: string,
  ) {}

  /** 恢复密钥 → Bearer token（客户端自选 challenge 并签名） */
  async exchangeRecoveryKey(recoveryKey: string): Promise<Credentials> {
    const secret = decodeSecretKey(recoveryKey)
    const auth = createAuthChallenge(secret)
    const body = await this.post('/v1/auth', {
      challenge: Buffer.from(auth.challenge).toString('base64'),
      signature: Buffer.from(auth.signature).toString('base64'),
      publicKey: Buffer.from(auth.publicKey).toString('base64'),
    })
    const token = (body as { token?: string }).token
    if (!token) throw new HttpApiError(200, '/v1/auth', 'response missing token')
    return { token, secret: encodeSecretKeyBase64Url(secret) }
  }

  /** 注册（或幂等重建）机器记录；metadata/daemonState 用机器 dataKey 加密 */
  async registerMachine(
    credentials: Credentials,
    identity: MachineIdentity,
    metadata: MachineMetadata,
  ): Promise<void> {
    const secret = secretOf(credentials)
    const machineKey = new Uint8Array(Buffer.from(identity.machineKey, 'base64'))
    const contentPublicKey = contentBoxPublicKey(secret)
    await this.post(
      '/v1/machines',
      {
        id: identity.machineId,
        metadata: encryptRecord(metadata, machineKey, 'dataKey'),
        daemonState: encryptRecord({ status: 'running', startedAt: Date.now() }, machineKey, 'dataKey'),
        dataEncryptionKey: wrapDataKeyForPublicKey(machineKey, contentPublicKey),
      },
      credentials.token,
    )
  }

  /** 创建会话记录，返回句柄（内容密钥随机生成，用账户内容公钥打包上报） */
  async createSession(
    credentials: Credentials,
    metadata: Record<string, unknown>,
    agentState: Record<string, unknown> | null = null,
  ): Promise<SessionHandle> {
    const secret = secretOf(credentials)
    const encryptionKey = new Uint8Array(randomBytes(32))
    const tag = randomUUID()
    const body = (await this.post(
      '/v1/sessions',
      {
        tag,
        metadata: encryptRecord(metadata, encryptionKey, 'dataKey'),
        agentState: agentState ? encryptRecord(agentState, encryptionKey, 'dataKey') : null,
        dataEncryptionKey: wrapDataKeyForPublicKey(encryptionKey, contentBoxPublicKey(secret)),
      },
      credentials.token,
    )) as { session?: { id: string; seq?: number } }
    const session = body.session
    if (!session?.id) throw new HttpApiError(200, '/v1/sessions', 'response missing session id')
    return { id: session.id, tag, seq: session.seq ?? 0, encryptionKey, encryptionVariant: 'dataKey' }
  }

  /** 批量上行加密消息（≤50 条/批） */
  async postMessages(credentials: Credentials, session: SessionHandle, contents: unknown[]): Promise<void> {
    for (let offset = 0; offset < contents.length; offset += 50) {
      const batch = contents.slice(offset, offset + 50).map((content) => ({
        content: encryptRecord(content, session.encryptionKey, session.encryptionVariant),
        localId: randomUUID(),
      }))
      await this.post(`/v3/sessions/${session.id}/messages`, { messages: batch }, credentials.token)
    }
  }

  /** 增量拉取并解密会话消息 */
  async fetchMessages(
    credentials: Credentials,
    session: SessionHandle,
    afterSeq: number,
  ): Promise<EncryptedMessageRecord[]> {
    const body = (await this.get(
      `/v3/sessions/${session.id}/messages?after_seq=${afterSeq}&limit=100`,
      credentials.token,
    )) as { messages?: Array<{ id: string; seq: number; content: string; localId?: string }> }
    return (body.messages ?? []).map((record) => ({
      id: record.id,
      seq: record.seq,
      localId: record.localId,
      content: decryptRecord(record.content, session.encryptionKey, session.encryptionVariant),
    }))
  }

  private async post(path: string, body: unknown, token?: string): Promise<unknown> {
    return this.request('POST', path, body, token)
  }

  private async get(path: string, token?: string): Promise<unknown> {
    return this.request('GET', path, undefined, token)
  }

  private async request(method: string, path: string, body: unknown, token?: string): Promise<unknown> {
    const headers: Record<string, string> = {
      'X-Happy-Client': this.clientId,
      Accept: 'application/json',
    }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (token) headers.Authorization = `Bearer ${token}`
    const response = await fetch(`${this.serverUrl.replace(/\/$/, '')}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await response.text()
    if (!response.ok) throw new HttpApiError(response.status, path, text.slice(0, 300))
    try {
      return JSON.parse(text)
    } catch {
      throw new HttpApiError(response.status, path, `invalid JSON: ${text.slice(0, 120)}`)
    }
  }
}

export function secretOf(credentials: Credentials): Uint8Array {
  return decodeSecretKey(credentials.secret)
}

export function contentBoxPublicKey(accountSecret: Uint8Array): Uint8Array {
  return nacl.box.keyPair.fromSecretKey(deriveContentBoxSecretKey(accountSecret)).publicKey
}
