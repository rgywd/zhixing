/**
 * 会话运行时：session-scoped socket、Session Protocol v2 上行、
 * agentState 审批请求发布、手机端 permission/abort RPC 接收。
 */
import { io, type Socket } from 'socket.io-client'
import { randomUUID } from 'node:crypto'
import { decryptRecord, encryptRecord } from './crypto.js'
import { HappyApi } from './api.js'
import { log } from './daemon.js'
import type { Credentials, SessionEnvelope, SessionEvent, SessionHandle } from './types.js'

const KEEPALIVE_INTERVAL_MS = 15_000

export interface PermissionAnswer {
  approved: boolean
  decision?: 'approved' | 'approved_for_session' | 'denied' | 'abort'
}

export interface SessionRuntimeOptions {
  serverUrl: string
  clientId: string
  credentials: Credentials
  session: SessionHandle
  /** 手机侧发来的新用户消息（已解密拆包） */
  onUserMessage: (text: string, meta: Record<string, unknown>) => void
  onAbort: () => void
}

export class SessionRuntime {
  private readonly socket: Socket
  private readonly api: HappyApi
  private keepalive: NodeJS.Timeout | null = null
  private agentStateVersion = 0
  private agentState: Record<string, unknown> = {}
  private lastSeq: number
  private fetching = false
  private turnCounter = 0
  thinking = false
  private readonly pendingPermissions = new Map<string, (answer: PermissionAnswer) => void>()

  constructor(private readonly options: SessionRuntimeOptions) {
    this.api = new HappyApi(options.serverUrl, options.clientId)
    this.lastSeq = options.session.seq
    this.socket = io(options.serverUrl.replace(/^http/, 'ws'), {
      path: '/v1/updates',
      transports: ['websocket'],
      auth: {
        token: options.credentials.token,
        clientType: 'session-scoped',
        sessionId: options.session.id,
        happyClient: options.clientId,
      },
      reconnection: true,
      reconnectionDelay: 1_000,
      reconnectionDelayMax: 10_000,
      autoConnect: false,
    })
    this.socket.on('connect', () => {
      this.registerRpc()
      this.startKeepalive()
      void this.pullMessages()
    })
    this.socket.on('disconnect', () => this.stopKeepalive())
    this.socket.on('update', () => void this.pullMessages())
    this.socket.on(
      'rpc-request',
      (payload: { method?: string; params?: string }, ack?: (result: string) => void) => {
        void this.dispatchRpc(payload, ack)
      },
    )
  }

  get sessionId(): string {
    return this.options.session.id
  }

  connect(): void {
    this.socket.connect()
  }

  async close(status: 'completed' | 'failed' | 'cancelled' = 'completed'): Promise<void> {
    this.stopKeepalive()
    this.socket.emit('session-end', { sid: this.sessionId, time: Date.now() })
    this.socket.disconnect()
  }

  beginTurn(): number {
    this.turnCounter += 1
    this.thinking = true
    return this.turnCounter
  }

  endTurn(): void {
    this.thinking = false
  }

  /** 上行一条 Session Protocol v2 事件 */
  async postEvent(event: SessionEvent, role: 'user' | 'agent' = 'agent'): Promise<void> {
    const envelope: SessionEnvelope = {
      id: randomUUID(),
      time: Date.now(),
      role,
      turn: this.turnCounter,
      ev: event,
    }
    await this.api.postMessages(this.options.credentials, this.options.session, [
      { role: 'session', content: envelope, meta: { sentFrom: 'cli' } },
    ])
  }

  /** 发布审批请求到 agentState.requests 并等待手机应答（或超时默认拒绝） */
  async requestPermission(
    id: string,
    tool: string,
    args: unknown,
    timeoutMs = 15 * 60_000,
  ): Promise<PermissionAnswer> {
    await this.mutateAgentState((state) => {
      const requests = { ...((state.requests as Record<string, unknown>) ?? {}) }
      requests[id] = { tool, arguments: args, createdAt: Date.now() }
      return { ...state, requests }
    })
    const answer = await new Promise<PermissionAnswer>((resolve) => {
      const timer = setTimeout(() => {
        this.pendingPermissions.delete(id)
        resolve({ approved: false, decision: 'denied' })
      }, timeoutMs)
      this.pendingPermissions.set(id, (value) => {
        clearTimeout(timer)
        this.pendingPermissions.delete(id)
        resolve(value)
      })
    })
    await this.mutateAgentState((state) => {
      const requests = { ...((state.requests as Record<string, unknown>) ?? {}) }
      const request = requests[id] as Record<string, unknown> | undefined
      delete requests[id]
      const completed = { ...((state.completedRequests as Record<string, unknown>) ?? {}) }
      completed[id] = {
        tool,
        arguments: args,
        createdAt: (request?.createdAt as number) ?? Date.now(),
        completedAt: Date.now(),
        status: answer.approved ? 'approved' : answer.decision === 'abort' ? 'canceled' : 'denied',
        decision: answer.decision,
      }
      return { ...state, requests, completedRequests: completed }
    })
    return answer
  }

  private registerRpc(): void {
    for (const method of ['permission', 'abort']) {
      this.socket.emit('rpc-register', { method: `${this.sessionId}:${method}` })
    }
  }

  private async dispatchRpc(
    payload: { method?: string; params?: string },
    ack?: (result: string) => void,
  ): Promise<void> {
    if (!ack || !payload.method) return
    const respond = (value: unknown) =>
      ack(encryptRecord(value, this.options.session.encryptionKey, this.options.session.encryptionVariant))
    const params =
      typeof payload.params === 'string'
        ? (decryptRecord(
            payload.params,
            this.options.session.encryptionKey,
            this.options.session.encryptionVariant,
          ) as Record<string, unknown> | null)
        : null
    const method = payload.method.split(':').pop()
    try {
      if (method === 'permission' && params) {
        const id = String(params.id ?? '')
        const resolver = this.pendingPermissions.get(id)
        if (resolver) {
          resolver({
            approved: params.approved === true,
            decision: params.decision as PermissionAnswer['decision'],
          })
          respond({ ok: true })
        } else {
          respond({ ok: false, error: `没有待处理的审批: ${id}` })
        }
      } else if (method === 'abort') {
        this.options.onAbort()
        respond({ ok: true })
      } else {
        respond({ ok: false, error: `未知方法: ${payload.method}` })
      }
    } catch (error) {
      respond({ ok: false, error: error instanceof Error ? error.message : String(error) })
    }
  }

  private async pullMessages(): Promise<void> {
    if (this.fetching) return
    this.fetching = true
    try {
      const records = await this.api.fetchMessages(
        this.options.credentials,
        this.options.session,
        this.lastSeq,
      )
      for (const record of records) {
        if (record.seq > this.lastSeq) this.lastSeq = record.seq
        const body = record.content as Record<string, unknown> | null
        if (!body || body.role !== 'user') continue
        const content = body.content as Record<string, unknown> | undefined
        if (content?.type !== 'text' || typeof content.text !== 'string') continue
        this.options.onUserMessage(content.text, (body.meta as Record<string, unknown>) ?? {})
      }
    } catch (error) {
      log(`拉取会话消息失败: ${error instanceof Error ? error.message : error}`)
    } finally {
      this.fetching = false
    }
  }

  private async mutateAgentState(
    mutate: (state: Record<string, unknown>) => Record<string, unknown>,
  ): Promise<void> {
    for (let attempt = 0; attempt < 5; attempt++) {
      const updated = mutate(this.agentState)
      const answer = (await this.socket.emitWithAck('update-state', {
        sid: this.sessionId,
        expectedVersion: this.agentStateVersion,
        agentState: encryptRecord(
          updated,
          this.options.session.encryptionKey,
          this.options.session.encryptionVariant,
        ),
      })) as { result?: string; version?: number; agentState?: string }
      if (answer.result === 'success') {
        this.agentState = updated
        this.agentStateVersion = answer.version ?? this.agentStateVersion + 1
        return
      }
      if (answer.result === 'version-mismatch' && typeof answer.version === 'number') {
        this.agentStateVersion = answer.version
        this.agentState = answer.agentState
          ? ((decryptRecord(
              answer.agentState,
              this.options.session.encryptionKey,
              this.options.session.encryptionVariant,
            ) as Record<string, unknown>) ?? {})
          : {}
        continue
      }
      return
    }
  }

  private startKeepalive(): void {
    this.stopKeepalive()
    this.keepalive = setInterval(() => {
      this.socket.volatile.emit('session-alive', {
        sid: this.sessionId,
        time: Date.now(),
        thinking: this.thinking,
        mode: 'remote',
      })
    }, KEEPALIVE_INTERVAL_MS)
  }

  private stopKeepalive(): void {
    if (this.keepalive) {
      clearInterval(this.keepalive)
      this.keepalive = null
    }
  }
}
