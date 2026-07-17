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

interface PendingRequest {
  resolve: (value: unknown) => void
  reject: (error: Error) => void
}

export type NotificationHandler = (method: string, params: unknown) => void
export type ServerRequestHandler = (method: string, params: unknown) => Promise<unknown>

export interface ThreadStartOptions {
  model?: string
  cwd: string
  approvalPolicy: string
  sandbox: string
  reasoningEffort?: string
}

export interface TurnStartOptions {
  threadId: string
  text: string
  model?: string
  effort?: string
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

  constructor(private readonly codexBinary = process.env.ZHIXING_CODEX_BIN ?? 'codex') {}

  onNotification(handler: NotificationHandler): void {
    this.notificationHandler = handler
  }

  onServerRequest(handler: ServerRequestHandler): void {
    this.serverRequestHandler = handler
  }

  async start(): Promise<void> {
    if (this.child) return
    const child = spawn(this.codexBinary, ['app-server'], {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: process.env,
    })
    this.child = child
    child.on('exit', (code, signal) => {
      this.exitError = new Error(`codex app-server exited (code=${code}, signal=${signal})`)
      for (const request of this.pending.values()) request.reject(this.exitError)
      this.pending.clear()
      this.child = null
    })
    child.stderr.on('data', (chunk: Buffer) => {
      const text = chunk.toString('utf8').trim()
      if (text) log(`[codex stderr] ${text.slice(0, 500)}`)
    })
    createInterface({ input: child.stdout }).on('line', (line) => this.handleLine(line))

    await this.request('initialize', {
      clientInfo: { name: 'zhixing-agent', title: 'Zhixing Agent', version: '0.1.0' },
      capabilities: { experimentalApi: false },
    })
    this.notify('initialized', undefined)
  }

  stop(): void {
    this.child?.kill('SIGTERM')
    this.child = null
  }

  async startThread(options: ThreadStartOptions): Promise<{ threadId: string; raw: unknown }> {
    const params: Record<string, unknown> = {
      cwd: options.cwd,
      approvalPolicy: options.approvalPolicy,
      sandbox: options.sandbox,
    }
    if (options.model) params.model = options.model
    if (options.reasoningEffort) params.config = { model_reasoning_effort: options.reasoningEffort }
    const result = (await this.request('thread/start', params)) as { thread?: { id?: string } }
    const threadId = result.thread?.id
    if (!threadId) throw new Error('thread/start response missing thread.id')
    return { threadId, raw: result }
  }

  async resumeThread(threadId: string, options: Partial<ThreadStartOptions> = {}): Promise<unknown> {
    const params: Record<string, unknown> = { threadId }
    if (options.cwd) params.cwd = options.cwd
    if (options.approvalPolicy) params.approvalPolicy = options.approvalPolicy
    if (options.sandbox) params.sandbox = options.sandbox
    if (options.model) params.model = options.model
    if (options.reasoningEffort) params.config = { model_reasoning_effort: options.reasoningEffort }
    return this.request('thread/resume', params)
  }

  async startTurn(options: TurnStartOptions): Promise<{ turnId: string | null }> {
    const params: Record<string, unknown> = {
      threadId: options.threadId,
      input: [{ type: 'text', text: options.text }],
    }
    if (options.model) params.model = options.model
    if (options.effort) params.effort = options.effort
    const result = (await this.request('turn/start', params)) as { turn?: { id?: string } }
    return { turnId: result.turn?.id ?? null }
  }

  async interruptTurn(threadId: string, turnId: string): Promise<void> {
    await this.request('turn/interrupt', { threadId, turnId })
  }

  private handleLine(line: string): void {
    const trimmed = line.trim()
    if (!trimmed) return
    let message: Record<string, unknown>
    try {
      message = JSON.parse(trimmed) as Record<string, unknown>
    } catch {
      log(`[codex] 无法解析的输出行: ${trimmed.slice(0, 200)}`)
      return
    }

    if ('id' in message && ('result' in message || 'error' in message)) {
      // 我们请求的响应
      const id = message.id as number
      const pending = this.pending.get(id)
      if (!pending) return
      this.pending.delete(id)
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
      this.pending.set(id, { resolve, reject })
      this.send(params === undefined ? { id, method } : { id, method, params })
    })
  }

  private notify(method: string, params: unknown): void {
    this.send(params === undefined ? { method } : { method, params })
  }

  private send(message: unknown): void {
    this.child?.stdin.write(`${JSON.stringify(message)}\n`)
  }
}
