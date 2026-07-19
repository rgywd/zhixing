/**
 * codex app-server v2 客户端（Thread/Turn/Item 模型）。
 *
 * 传输：`codex app-server` stdio，逐行 JSON（无 Content-Length 帧、无 jsonrpc 字段）。
 * 请求 {id, method, params}；通知无 id；服务端→客户端请求（审批）带 id，需回 {id, result}。
 * 协议事实来源：codex-rs/app-server/README.md 与 app-server-protocol 源码
 * （详见 agent/docs/PROTOCOL_NOTES.md 第 8 节）。
 */
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { createInterface } from 'node:readline'
import { log } from '../daemon.js'
import { resolveCodexBinary } from './binary.js'
import {
  parseInitializeInfo,
  parseThreadListPage,
  parseThreadReadResponse,
  type CodexInitializeInfo,
  type CodexThread,
  type CodexThreadListOptions,
  type CodexThreadListPage,
} from './protocol.js'

interface PendingRequest {
  resolve: (value: unknown) => void
  reject: (error: Error) => void
  timer: NodeJS.Timeout
}

export type NotificationHandler = (method: string, params: unknown) => void
export type ServerRequestHandler = (method: string, params: unknown) => Promise<unknown>

export interface ThreadStartOptions {
  model?: string
  cwd: string
  approvalPolicy: string
  sandbox: string
  reasoningEffort?: string
  serviceTier?: string
  permissions?: string
}

export interface TurnStartOptions {
  threadId: string
  input: Array<Record<string, unknown>>
  model?: string
  effort?: string
  serviceTier?: string
  permissions?: string
}

export class CodexAppServerClient {
  private child: ChildProcessWithoutNullStreams | null = null
  private nextId = 1
  private readonly pending = new Map<number, PendingRequest>()
  private notificationHandler: NotificationHandler = () => {}
  private serverRequestHandler: ServerRequestHandler = async (method) => {
    throw new Error(`Unhandled server request: ${method}`)
  }
  private exitError: Error | null = null
  private initializeInfo: CodexInitializeInfo | null = null

  constructor(
    private readonly codexBinary = resolveCodexBinary(),
    private readonly requestTimeoutMs = 30_000,
    private readonly logger: (message: string) => void = log,
  ) {}

  onNotification(handler: NotificationHandler): void {
    this.notificationHandler = handler
  }

  onServerRequest(handler: ServerRequestHandler): void {
    this.serverRequestHandler = handler
  }

  async start(): Promise<CodexInitializeInfo> {
    if (this.child && this.initializeInfo) return this.initializeInfo
    const child = spawn(this.codexBinary, ['app-server'], {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: process.env,
    })
    this.child = child
    child.on('error', (error) => this.failAll(new Error(`无法启动 codex app-server: ${error.message}`)))
    child.on('exit', (code, signal) => {
      this.failAll(new Error(`codex app-server exited (code=${code}, signal=${signal})`))
    })
    child.stderr.on('data', (chunk: Buffer) => {
      const text = chunk.toString('utf8').trim()
      if (text) this.logger(`[codex stderr] ${text.slice(0, 500)}`)
    })
    createInterface({ input: child.stdout }).on('line', (line) => this.handleLine(line))

    const initialized = await this.request('initialize', {
      clientInfo: { name: 'zhixing-agent', title: 'Zhixing Agent', version: '0.1.0' },
      capabilities: { experimentalApi: true },
    })
    this.initializeInfo = parseInitializeInfo(initialized)
    this.notify('initialized', undefined)
    return this.initializeInfo
  }

  stop(): void {
    this.child?.kill('SIGTERM')
    this.child = null
    this.initializeInfo = null
  }

  async startThread(options: ThreadStartOptions): Promise<{ threadId: string; raw: unknown }> {
    const params = buildThreadParams(options)
    const result = (await this.request('thread/start', params)) as { thread?: { id?: string } }
    const threadId = result.thread?.id
    if (!threadId) throw new Error('thread/start response missing thread.id')
    return { threadId, raw: result }
  }

  async resumeThread(threadId: string, options: Partial<ThreadStartOptions> = {}): Promise<unknown> {
    const params = buildThreadParams(options, threadId)
    return this.request('thread/resume', params)
  }

  async startTurn(options: TurnStartOptions): Promise<{ turnId: string | null }> {
    const params: Record<string, unknown> = {
      threadId: options.threadId,
      input: options.input,
    }
    if (options.model) params.model = options.model
    if (options.effort) params.effort = options.effort
    if (options.serviceTier) params.serviceTier = options.serviceTier
    if (options.permissions) params.permissions = options.permissions
    const result = (await this.request('turn/start', params)) as { turn?: { id?: string } }
    return { turnId: result.turn?.id ?? null }
  }

  async interruptTurn(threadId: string, turnId: string): Promise<void> {
    await this.request('turn/interrupt', { threadId, turnId })
  }

  async steerTurn(threadId: string, expectedTurnId: string, input: Array<Record<string, unknown>>): Promise<void> {
    await this.request('turn/steer', {
      threadId,
      expectedTurnId,
      input,
    })
  }

  async listModels(): Promise<unknown> {
    return this.request('model/list', { limit: 100, includeHidden: false })
  }

  async listPermissionProfiles(cwd: string): Promise<unknown> {
    return this.request('permissionProfile/list', { cwd, limit: 100 })
  }

  async listSkills(cwd: string): Promise<unknown> {
    return this.request('skills/list', { cwds: [cwd], forceReload: false })
  }

  async listPlugins(cwd: string): Promise<unknown> {
    return this.request('plugin/list', { cwds: [cwd] })
  }

  async listApps(): Promise<unknown> {
    return this.request('app/list', { limit: 100, forceRefetch: false })
  }

  async forkThread(threadId: string): Promise<{ threadId: string }> {
    const result = (await this.request('thread/fork', { threadId })) as { thread?: { id?: string } }
    const forkedThreadId = result.thread?.id
    if (!forkedThreadId) throw new Error('thread/fork response missing thread.id')
    return { threadId: forkedThreadId }
  }

  get serverInfo(): CodexInitializeInfo | null {
    return this.initializeInfo
  }

  get binaryPath(): string {
    return this.codexBinary
  }

  async listThreadsPage(options: CodexThreadListOptions = {}): Promise<CodexThreadListPage> {
    return parseThreadListPage(await this.request('thread/list', options))
  }

  async readThread(threadId: string, includeTurns = true): Promise<CodexThread> {
    return parseThreadReadResponse(await this.request('thread/read', { threadId, includeTurns }))
  }

  async archiveThread(threadId: string): Promise<void> {
    await this.request('thread/archive', { threadId })
  }

  async unarchiveThread(threadId: string): Promise<void> {
    await this.request('thread/unarchive', { threadId })
  }

  async deleteThread(threadId: string): Promise<void> {
    await this.request('thread/delete', { threadId })
  }

  private handleLine(line: string): void {
    const trimmed = line.trim()
    if (!trimmed) return
    let message: Record<string, unknown>
    try {
      message = JSON.parse(trimmed) as Record<string, unknown>
    } catch {
      this.logger(`[codex] 无法解析的输出行: ${trimmed.slice(0, 200)}`)
      return
    }

    if ('id' in message && ('result' in message || 'error' in message)) {
      // 我们请求的响应
      const id = message.id as number
      const pending = this.pending.get(id)
      if (!pending) return
      this.pending.delete(id)
      clearTimeout(pending.timer)
      if ('error' in message && message.error) {
        const error = message.error as { code?: number; message?: string }
        pending.reject(new Error(`codex RPC error ${error.code}: ${error.message}`))
      } else {
        pending.resolve(message.result)
      }
      return
    }

    if ('id' in message && typeof message.method === 'string') {
      // 服务端→客户端请求（审批等），必须应答
      const { id, method, params } = message as { id: unknown; method: string; params?: unknown }
      this.serverRequestHandler(method, params)
        .then((result) => this.send({ id, result: result ?? {} }))
        .catch((error: unknown) => {
          this.send({
            id,
            error: { code: -32000, message: error instanceof Error ? error.message : String(error) },
          })
        })
      return
    }

    if (typeof message.method === 'string') {
      this.notificationHandler(message.method, message.params)
    }
  }

  private request(method: string, params: unknown): Promise<unknown> {
    if (!this.child) return Promise.reject(this.exitError ?? new Error('codex app-server not started'))
    const id = this.nextId++
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`codex RPC timeout: ${method}`))
      }, this.requestTimeoutMs)
      this.pending.set(id, { resolve, reject, timer })
      this.send(params === undefined ? { id, method } : { id, method, params })
    })
  }

  private notify(method: string, params: unknown): void {
    this.send(params === undefined ? { method } : { method, params })
  }

  private send(message: unknown): void {
    this.child?.stdin.write(`${JSON.stringify(message)}\n`)
  }

  private failAll(error: Error): void {
    this.exitError = error
    for (const request of this.pending.values()) {
      clearTimeout(request.timer)
      request.reject(error)
    }
    this.pending.clear()
    this.child = null
    this.initializeInfo = null
  }
}

export function buildThreadParams(
  options: Partial<ThreadStartOptions>,
  threadId?: string,
): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  if (threadId) params.threadId = threadId
  if (options.cwd) params.cwd = options.cwd
  if (options.approvalPolicy) params.approvalPolicy = options.approvalPolicy
  if (options.model) params.model = options.model
  if (options.reasoningEffort) params.config = { model_reasoning_effort: options.reasoningEffort }
  if (options.serviceTier) params.serviceTier = options.serviceTier
  // App Server explicitly rejects a named permissions profile combined with
  // legacy sandbox. Profiles are authoritative when selected.
  if (options.permissions) params.permissions = options.permissions
  else if (options.sandbox) params.sandbox = options.sandbox
  return params
}
