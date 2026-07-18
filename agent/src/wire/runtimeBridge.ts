import { randomUUID } from 'node:crypto'
import { normalizeThreadDetail } from '../catalog/threadDetail.js'
import {
  CodexAppServerClient,
  type ServerRequestHandler,
  type ThreadStartOptions,
  type TurnStartOptions,
} from '../codex/appServerClient.js'
import type { CodexThread } from '../codex/protocol.js'
import { analyzeCodexCompatibility } from '../codex/compatibility.js'
import { generateCodexSchemaHash } from '../codex/schemaHash.js'
import { log } from '../daemon.js'
import type { WirePayload } from './relayClient.js'

export type RuntimeCommandName =
  | 'thread.detail'
  | 'thread.start'
  | 'thread.resume'
  | 'thread.fork'
  | 'thread.archive'
  | 'thread.unarchive'
  | 'thread.delete'
  | 'turn.start'
  | 'turn.steer'
  | 'turn.interrupt'
  | 'approval.resolve'

export interface RuntimeCommand {
  command: RuntimeCommandName
  machineId: string
  threadId?: string
  cwd?: string
  text?: string
  confirmedUnknown?: boolean
  approvalId?: string
  decision?: 'accept' | 'decline' | 'cancel'
  model?: string
  effort?: string
  approvalPolicy?: string
  sandbox?: string
}

interface RuntimeBinding {
  bindingId: string
  threadId: string
  client: RuntimeCodexClient
  activeTurnId: string | null
  deltas: Map<string, string>
}

interface PendingApproval {
  resolve: (decision: string) => void
  threadId: string
}

export interface RuntimeBridgeOptions {
  machineId: string
  clientFactory?: () => RuntimeCodexClient
}

export interface RuntimeCodexClient {
  start(): Promise<unknown>
  stop(): void
  onNotification(handler: (method: string, params: unknown) => void): void
  onServerRequest(handler: ServerRequestHandler): void
  startThread(options: ThreadStartOptions): Promise<{ threadId: string; raw: unknown }>
  resumeThread(threadId: string, options?: Partial<ThreadStartOptions>): Promise<unknown>
  forkThread(threadId: string): Promise<{ threadId: string }>
  startTurn(options: TurnStartOptions): Promise<{ turnId: string | null }>
  steerTurn(threadId: string, expectedTurnId: string, text: string): Promise<void>
  interruptTurn(threadId: string, turnId: string): Promise<void>
  readThread(threadId: string, includeTurns?: boolean): Promise<CodexThread>
  archiveThread(threadId: string): Promise<void>
  unarchiveThread(threadId: string): Promise<void>
  deleteThread(threadId: string): Promise<void>
}

export interface RuntimeRelay {
  poll(limit?: number): Promise<{ payloads: WirePayload[]; gapDetected: boolean }>
  publish(type: string, body: unknown, streamPrefix: string, requestId?: string | null): Promise<number>
  requestState(requestId: string): {
    state: 'inflight' | 'completed'
    updatedAt: number
    result?: unknown
    error?: string
  } | undefined
  markRequest(requestId: string, state: 'inflight' | 'completed', result?: unknown, error?: string): void
}

export class WireCodexRuntimeBridge {
  private readonly runtimes = new Map<string, RuntimeBinding>()
  private readonly approvals = new Map<string, PendingApproval>()
  private readonly clientFactory: () => RuntimeCodexClient

  constructor(
    private readonly relay: RuntimeRelay,
    private readonly options: RuntimeBridgeOptions,
  ) {
    this.clientFactory = options.clientFactory ?? (() => new CodexAppServerClient())
  }

  async pollOnce(): Promise<number> {
    const { payloads, gapDetected } = await this.relay.poll(200)
    if (gapDetected) throw new Error('Android 控制请求序列不连续')
    let handled = 0
    for (const payload of payloads) {
      if (payload.type !== 'runtime.command') continue
      await this.handlePayload(payload)
      handled += 1
    }
    return handled
  }

  async shutdown(): Promise<void> {
    for (const runtime of this.runtimes.values()) runtime.client.stop()
    this.runtimes.clear()
    for (const approval of this.approvals.values()) approval.resolve('cancel')
    this.approvals.clear()
  }

  private async handlePayload(payload: WirePayload): Promise<void> {
    const requestId = requireString(payload.requestId, 'requestId')
    const command = parseRuntimeCommand(payload.body)
    if (command.machineId !== this.options.machineId) return
    const prior = this.relay.requestState(requestId)
    if (prior?.state === 'completed') {
      await this.publishResult(requestId, command, prior.error === undefined, prior.result, prior.error)
      return
    }
    if (prior?.state === 'inflight') {
      await this.publishResult(requestId, command, false, undefined, '请求上次执行时中断，为避免重复操作未自动重试')
      return
    }
    this.relay.markRequest(requestId, 'inflight')
    try {
      const result = await this.execute(command, requestId)
      this.relay.markRequest(requestId, 'completed', result)
      await this.publishResult(requestId, command, true, result)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.relay.markRequest(requestId, 'completed', undefined, message)
      await this.publishResult(requestId, command, false, undefined, message)
    }
  }

  private async execute(command: RuntimeCommand, requestId: string): Promise<unknown> {
    switch (command.command) {
      case 'thread.detail': {
        const threadId = requireString(command.threadId, 'threadId')
        const runtime = this.runtimes.get(threadId)
        const client = runtime?.client ?? this.clientFactory()
        if (!runtime) await this.startClient(client)
        try {
          const detail = normalizeThreadDetail(this.options.machineId, await client.readThread(threadId, true))
          await this.relay.publish('thread.detail', detail, `detail_${this.options.machineId}_${threadId}`, requestId)
          return { threadId }
        } finally {
          if (!runtime) client.stop()
        }
      }
      case 'thread.start': {
        const client = this.clientFactory()
        await this.startClient(client)
        const started = await client.startThread({
          cwd: requireString(command.cwd, 'cwd'),
          approvalPolicy: command.approvalPolicy ?? 'untrusted',
          sandbox: command.sandbox ?? 'workspace-write',
          model: command.model,
          reasoningEffort: command.effort,
        })
        const runtime = this.bind(client, started.threadId)
        await this.publishThreadDetail(runtime, false)
        await this.publishEvent(started.threadId, 'runtime.connected', {
          bindingId: runtime.bindingId,
          state: 'idle',
        })
        if (command.text?.trim()) await this.startTurn(runtime, command.text, command)
        return { threadId: started.threadId, bindingId: runtime.bindingId }
      }
      case 'thread.resume':
      case 'turn.start': {
        const threadId = requireString(command.threadId, 'threadId')
        let runtime = this.runtimes.get(threadId)
        if (!runtime) {
          if (!command.confirmedUnknown) throw new Error('该任务由桌面端创建，继续前需要用户确认接管')
          const client = this.clientFactory()
          await this.startClient(client)
          await client.resumeThread(threadId, {
            approvalPolicy: command.approvalPolicy ?? 'untrusted',
            sandbox: command.sandbox ?? 'workspace-write',
            model: command.model,
            reasoningEffort: command.effort,
          })
          runtime = this.bind(client, threadId)
        }
        if (command.text?.trim()) await this.startTurn(runtime, command.text, command)
        return { threadId, bindingId: runtime.bindingId }
      }
      case 'thread.fork': {
        const sourceId = requireString(command.threadId, 'threadId')
        const client = this.clientFactory()
        await this.startClient(client)
        const forked = await client.forkThread(sourceId)
        const runtime = this.bind(client, forked.threadId)
        await this.publishThreadDetail(runtime, false)
        await this.publishEvent(forked.threadId, 'runtime.connected', {
          bindingId: runtime.bindingId,
          state: 'idle',
        })
        return { threadId: forked.threadId, bindingId: runtime.bindingId, forkedFromId: sourceId }
      }
      case 'turn.steer': {
        const runtime = this.requireRuntime(command.threadId)
        await runtime.client.steerTurn(
          runtime.threadId,
          requireString(runtime.activeTurnId, 'activeTurnId'),
          requireString(command.text, 'text'),
        )
        return { threadId: runtime.threadId }
      }
      case 'turn.interrupt': {
        const runtime = this.requireRuntime(command.threadId)
        await runtime.client.interruptTurn(runtime.threadId, requireString(runtime.activeTurnId, 'activeTurnId'))
        return { threadId: runtime.threadId }
      }
      case 'approval.resolve': {
        const approvalId = requireString(command.approvalId, 'approvalId')
        const approval = this.approvals.get(approvalId)
        if (!approval) throw new Error('审批已失效或已处理')
        approval.resolve(command.decision ?? 'decline')
        this.approvals.delete(approvalId)
        await this.publishEvent(approval.threadId, 'approval.resolved', { approvalId, decision: command.decision ?? 'decline' })
        return { threadId: approval.threadId, approvalId }
      }
      case 'thread.archive':
      case 'thread.unarchive':
      case 'thread.delete': {
        const threadId = requireString(command.threadId, 'threadId')
        const runtime = this.runtimes.get(threadId)
        const client = runtime?.client ?? this.clientFactory()
        if (!runtime) await this.startClient(client)
        try {
          if (command.command === 'thread.archive') await client.archiveThread(threadId)
          if (command.command === 'thread.unarchive') await client.unarchiveThread(threadId)
          if (command.command === 'thread.delete') await client.deleteThread(threadId)
          await this.publishEvent(threadId, command.command.replace('thread.', 'thread.'), {})
          if (command.command === 'thread.delete') this.removeRuntime(threadId)
          return { threadId }
        } finally {
          if (!runtime) client.stop()
        }
      }
    }
  }

  private bind(client: RuntimeCodexClient, threadId: string): RuntimeBinding {
    const existing = this.runtimes.get(threadId)
    if (existing) {
      client.stop()
      return existing
    }
    const runtime: RuntimeBinding = {
      bindingId: randomUUID(),
      threadId,
      client,
      activeTurnId: null,
      deltas: new Map(),
    }
    client.onNotification((method, params) => {
      void this.handleNotification(runtime, method, (params ?? {}) as Record<string, unknown>).catch((error) => {
        log(`Wire runtime 事件处理失败 ${method}: ${error instanceof Error ? error.message : String(error)}`)
      })
    })
    client.onServerRequest(this.approvalHandler(runtime))
    this.runtimes.set(threadId, runtime)
    void this.publishEvent(threadId, 'runtime.connected', { bindingId: runtime.bindingId, state: 'idle' })
      .catch((error) => log(`Wire runtime 连接事件发布失败: ${error instanceof Error ? error.message : String(error)}`))
    return runtime
  }

  private async startClient(client: RuntimeCodexClient): Promise<void> {
    await client.start()
    if (client instanceof CodexAppServerClient) {
      const info = client.serverInfo
      if (!info) throw new Error('codex app-server 初始化信息缺失')
      const compatibility = analyzeCodexCompatibility(
        info,
        await generateCodexSchemaHash(client.binaryPath),
      )
      if (!compatibility.runtimeWritable) {
        client.stop()
        throw new Error(compatibility.reason ?? '当前 Codex 版本仅支持读取历史')
      }
    }
  }

  private async startTurn(runtime: RuntimeBinding, text: string, command: RuntimeCommand): Promise<void> {
    if (runtime.activeTurnId) throw new Error('任务正在运行，请使用“补充要求”或先停止')
    const turn = await runtime.client.startTurn({
      threadId: runtime.threadId,
      text,
      model: command.model,
      effort: command.effort,
    })
    runtime.activeTurnId = turn.turnId
  }

  private async handleNotification(
    runtime: RuntimeBinding,
    method: string,
    params: Record<string, unknown>,
  ): Promise<void> {
    if (method === 'turn/started') {
      const turn = params.turn as Record<string, unknown> | undefined
      runtime.activeTurnId = String(turn?.id ?? runtime.activeTurnId ?? '') || null
      await this.publishEvent(runtime.threadId, 'turn.started', { turnId: runtime.activeTurnId })
      return
    }
    if (method === 'item/agentMessage/delta') {
      const itemId = String(params.itemId ?? '')
      const text = (runtime.deltas.get(itemId) ?? '') + String(params.delta ?? '')
      runtime.deltas.set(itemId, text)
      await this.publishEvent(runtime.threadId, 'item.delta', {
        turnId: runtime.activeTurnId,
        itemId,
        itemType: 'agentMessage',
        role: 'agent',
        text,
      })
      return
    }
    if (method === 'item/started' || method === 'item/completed') {
      const item = (params.item ?? {}) as Record<string, unknown>
      const itemId = String(item.id ?? '')
      const type = String(item.type ?? 'unknown')
      const text = type === 'agentMessage'
        ? String(item.text ?? runtime.deltas.get(itemId) ?? '')
        : summarizeItem(item)
      await this.publishEvent(runtime.threadId, method === 'item/started' ? 'item.started' : 'item.completed', {
        turnId: runtime.activeTurnId,
        itemId,
        itemType: type,
        role: type === 'agentMessage' ? 'agent' : type === 'userMessage' ? 'user' : 'tool',
        text,
        status: item.status ?? null,
      })
      if (method === 'item/completed') runtime.deltas.delete(itemId)
      return
    }
    if (method === 'turn/completed') {
      const turn = (params.turn ?? {}) as Record<string, unknown>
      const turnId = String(turn.id ?? runtime.activeTurnId ?? '')
      runtime.activeTurnId = null
      await this.publishEvent(runtime.threadId, 'turn.completed', { turnId, status: turn.status ?? 'completed' })
      await this.publishThreadDetail(runtime)
      return
    }
    if (method === 'error') {
      await this.publishEvent(runtime.threadId, 'error', {
        message: String((params as { message?: unknown }).message ?? 'Codex 执行失败'),
      })
    }
  }

  private approvalHandler(runtime: RuntimeBinding): ServerRequestHandler {
    return async (method, rawParams) => {
      if (!method.endsWith('/requestApproval')) throw new Error(`未支持的服务端请求: ${method}`)
      const params = (rawParams ?? {}) as Record<string, unknown>
      const approvalId = String(params.approvalId ?? params.itemId ?? randomUUID())
      const decision = await new Promise<string>((resolve) => {
        this.approvals.set(approvalId, { resolve, threadId: runtime.threadId })
        void this.publishEvent(runtime.threadId, 'approval.requested', {
          approvalId,
          kind: method.includes('commandExecution') ? 'command' : method.includes('fileChange') ? 'file' : 'permissions',
          summary: approvalSummary(method, params),
          payload: sanitizeApproval(params),
        }).catch((error) => {
          log(`Wire runtime 审批事件发布失败: ${error instanceof Error ? error.message : String(error)}`)
          this.approvals.delete(approvalId)
          resolve('decline')
        })
      })
      if (method.includes('permissions/')) return { permissions: decision === 'accept' ? {} : null }
      return { decision }
    }
  }

  private requireRuntime(threadId: string | undefined): RuntimeBinding {
    const id = requireString(threadId, 'threadId')
    const runtime = this.runtimes.get(id)
    if (!runtime) throw new Error('任务尚未由手机接管')
    return runtime
  }

  private removeRuntime(threadId: string): void {
    this.runtimes.get(threadId)?.client.stop()
    this.runtimes.delete(threadId)
  }

  private async publishThreadDetail(runtime: RuntimeBinding, includeTurns = true): Promise<void> {
    const detail = normalizeThreadDetail(
      this.options.machineId,
      await runtime.client.readThread(runtime.threadId, includeTurns),
    )
    await this.relay.publish(
      'thread.detail',
      detail,
      `detail_${this.options.machineId}_${runtime.threadId}`,
    )
  }

  private async publishEvent(threadId: string, type: string, data: Record<string, unknown>): Promise<void> {
    await this.relay.publish('runtime.event', {
      machineId: this.options.machineId,
      threadId,
      eventId: randomUUID(),
      type,
      at: Date.now(),
      ...data,
    }, `runtime_${this.options.machineId}_${threadId}`)
  }

  private async publishResult(
    requestId: string,
    command: RuntimeCommand,
    ok: boolean,
    result?: unknown,
    error?: string,
  ): Promise<void> {
    await this.relay.publish('command.result', {
      machineId: this.options.machineId,
      threadId: command.threadId ?? null,
      command: command.command,
      ok,
      result: result ?? null,
      error: error ?? null,
    }, `result_${this.options.machineId}`, requestId)
  }
}

function parseRuntimeCommand(value: unknown): RuntimeCommand {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('runtime.command body 格式错误')
  const body = value as Record<string, unknown>
  return {
    ...body,
    command: requireString(body.command, 'command') as RuntimeCommandName,
    machineId: requireString(body.machineId, 'machineId'),
  } as RuntimeCommand
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`${field} 不能为空`)
  return value
}

function summarizeItem(item: Record<string, unknown>): string {
  if (item.type === 'userMessage') return userMessageText(item.content)
  if (item.type === 'commandExecution') return String(item.command ?? '执行命令')
  if (item.type === 'fileChange') return '修改文件'
  if (item.type === 'reasoning') {
    return Array.isArray(item.summary) ? item.summary.join('\n') : String(item.summary ?? '思考')
  }
  return String(item.text ?? item.type ?? '活动')
}

function userMessageText(value: unknown): string {
  if (!Array.isArray(value)) return ''
  return value
    .map((part) => {
      if (!part || typeof part !== 'object' || Array.isArray(part)) return ''
      const record = part as Record<string, unknown>
      return record.type === 'text' ? String(record.text ?? '') : ''
    })
    .filter(Boolean)
    .join('\n')
}

function approvalSummary(method: string, params: Record<string, unknown>): string {
  if (method.includes('commandExecution')) return String(params.command ?? params.reason ?? '执行命令')
  if (method.includes('fileChange')) return String(params.grantRoot ?? params.reason ?? '修改文件')
  return String(params.reason ?? '请求额外权限')
}

function sanitizeApproval(params: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(params)
      .filter(([key]) => ['command', 'cwd', 'reason', 'grantRoot', 'itemId', 'turnId'].includes(key))
      .map(([key, value]) => [key, typeof value === 'string' ? value.slice(0, 4_000) : value]),
  )
}
