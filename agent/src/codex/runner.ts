/**
 * Codex 会话运行器：codex app-server v2 事件流 ⇄ Session Protocol v2 ⇄ 手机端。
 */
import { hostname } from 'node:os'
import { HappyApi } from '../api.js'
import { SessionRuntime } from '../session.js'
import { CodexAppServerClient } from './appServerClient.js'
import { resolveExecutionPolicy, normalizeMode } from './executionPolicy.js'
import { HardLimits, normalizeRules } from './hardLimits.js'
import { log } from '../daemon.js'
import type { Credentials, SpawnParams } from '../types.js'

export interface RunnerContext {
  serverUrl: string
  clientId: string
  machineId: string
  credentials: Credentials
}

interface ActiveTurn {
  turnId: string | null
}

export class CodexSessionRunner {
  private runtime!: SessionRuntime
  private readonly codex = new CodexAppServerClient()
  private threadId!: string
  private activeTurn: ActiveTurn | null = null
  private policy = resolveExecutionPolicy(undefined)
  private hardLimits = new HardLimits([])
  private model: string | undefined
  private effort: string | undefined
  private readonly queue: Array<{ text: string; meta: Record<string, unknown> }> = []
  private running = false
  onClosed: (sessionId: string) => void = () => {}

  private constructor(private readonly context: RunnerContext) {}

  get sessionId(): string {
    return this.runtime.sessionId
  }

  static async spawn(context: RunnerContext, params: SpawnParams): Promise<CodexSessionRunner> {
    const runner = new CodexSessionRunner(context)
    runner.hardLimits = new HardLimits(normalizeRules((params as { disallowedTools?: unknown }).disallowedTools))
    runner.policy = resolveExecutionPolicy(params.permissionMode, !runner.hardLimits.isEmpty)
    runner.model = params.modelMode || undefined
    runner.effort = params.effortLevel || undefined

    await runner.codex.start()
    runner.bindCodexHandlers()

    let threadId: string
    if (params.resumeCodexThreadId) {
      await runner.codex.resumeThread(params.resumeCodexThreadId, {
        cwd: params.directory,
        approvalPolicy: runner.policy.approvalPolicy,
        sandbox: runner.policy.sandbox,
        model: runner.model,
        reasoningEffort: runner.effort,
      })
      threadId = params.resumeCodexThreadId
    } else {
      const started = await runner.codex.startThread({
        cwd: params.directory,
        approvalPolicy: runner.policy.approvalPolicy,
        sandbox: runner.policy.sandbox,
        model: runner.model,
        reasoningEffort: runner.effort,
      })
      threadId = started.threadId
    }
    runner.threadId = threadId

    const api = new HappyApi(context.serverUrl, context.clientId)
    const session = await api.createSession(context.credentials, {
      path: params.directory,
      host: hostname(),
      machineId: context.machineId,
      flavor: 'codex',
      codexThreadId: threadId,
      startedBy: 'daemon',
      startedFromDaemon: true,
      permissionMode: normalizeMode(params.permissionMode),
    })

    runner.runtime = new SessionRuntime({
      serverUrl: context.serverUrl,
      clientId: context.clientId,
      credentials: context.credentials,
      session,
      onUserMessage: (text, meta) => runner.enqueue(text, meta),
      onAbort: () => void runner.interrupt(),
    })
    runner.runtime.connect()
    log(`Codex 会话已建立: session=${session.id} thread=${threadId} mode=${normalizeMode(params.permissionMode)}`)
    return runner
  }

  async stop(): Promise<void> {
    await this.interrupt()
    await this.runtime.close()
    this.codex.stop()
    this.onClosed(this.sessionId)
  }

  private enqueue(text: string, meta: Record<string, unknown>): void {
    this.queue.push({ text, meta })
    void this.drainQueue()
  }

  private async drainQueue(): Promise<void> {
    if (this.running) return
    this.running = true
    try {
      let next: { text: string; meta: Record<string, unknown> } | undefined
      while ((next = this.queue.shift())) {
        await this.runTurn(next.text, next.meta)
      }
    } finally {
      this.running = false
    }
  }

  private async runTurn(text: string, meta: Record<string, unknown>): Promise<void> {
    if (typeof meta.model === 'string' && meta.model) this.model = meta.model
    const effortMeta = meta.reasoningEffort ?? meta.effortLevel
    if (typeof effortMeta === 'string' && effortMeta) this.effort = effortMeta
    if (meta.disallowedTools !== undefined) {
      // 每条消息 meta 携带预设规则，随消息更新（与 happy CLI 的 meta 粘滞语义一致）
      this.hardLimits = new HardLimits(normalizeRules(meta.disallowedTools))
    }
    this.runtime.beginTurn()
    try {
      const turn = await this.codex.startTurn({
        threadId: this.threadId,
        text,
        model: this.model,
        effort: this.effort,
      })
      this.activeTurn = { turnId: turn.turnId }
      await this.waitTurnEnd()
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      await this.runtime.postEvent({ t: 'service', kind: 'error', text: `执行失败: ${message}` })
      await this.runtime.postEvent({ t: 'turn-end', status: 'failed' })
      this.runtime.endTurn()
      this.activeTurn = null
    }
  }

  private turnEndResolvers: Array<() => void> = []

  private waitTurnEnd(): Promise<void> {
    if (!this.activeTurn) return Promise.resolve()
    return new Promise((resolve) => this.turnEndResolvers.push(resolve))
  }

  private settleTurn(): void {
    this.activeTurn = null
    this.runtime.endTurn()
    for (const resolve of this.turnEndResolvers.splice(0)) resolve()
  }

  async interrupt(): Promise<void> {
    const turn = this.activeTurn
    if (turn?.turnId) {
      try {
        await this.codex.interruptTurn(this.threadId, turn.turnId)
      } catch (error) {
        log(`中断失败: ${error instanceof Error ? error.message : error}`)
      }
    }
  }

  private bindCodexHandlers(): void {
    const deltas = new Map<string, string>()

    this.codex.onNotification((method, rawParams) => {
      const params = (rawParams ?? {}) as Record<string, unknown>
      void this.handleNotification(method, params, deltas).catch((error) => {
        log(`事件处理失败 ${method}: ${error instanceof Error ? error.message : error}`)
      })
    })

    this.codex.onServerRequest(async (method, rawParams) => {
      const params = (rawParams ?? {}) as Record<string, unknown>
      if (method === 'item/commandExecution/requestApproval') {
        return { decision: await this.decideApproval('shell', params) }
      }
      if (method === 'item/fileChange/requestApproval') {
        return { decision: await this.decideApproval('edit_file', params) }
      }
      if (method === 'item/permissions/requestApproval') {
        return { permissions: {} }
      }
      throw new Error(`未支持的服务端请求: ${method}`)
    })
  }

  private async handleNotification(
    method: string,
    params: Record<string, unknown>,
    deltas: Map<string, string>,
  ): Promise<void> {
    switch (method) {
      case 'turn/started': {
        const turn = params.turn as { id?: string } | undefined
        if (this.activeTurn && turn?.id) this.activeTurn.turnId = turn.id
        await this.runtime.postEvent({ t: 'turn-start' })
        return
      }
      case 'turn/completed': {
        const turn = params.turn as { status?: string } | undefined
        const status =
          turn?.status === 'failed' ? 'failed' : turn?.status === 'interrupted' ? 'cancelled' : 'completed'
        await this.runtime.postEvent({ t: 'turn-end', status })
        this.settleTurn()
        return
      }
      case 'item/agentMessage/delta': {
        const itemId = String(params.itemId ?? '')
        deltas.set(itemId, (deltas.get(itemId) ?? '') + String(params.delta ?? ''))
        return
      }
      case 'item/started': {
        const item = params.item as Record<string, unknown> | undefined
        if (item?.type === 'commandExecution') {
          await this.runtime.postEvent({
            t: 'tool-call-start',
            call: String(item.id ?? ''),
            name: 'shell',
            title: typeof item.command === 'string' ? item.command : '执行命令',
            args: { command: item.command, cwd: item.cwd },
          })
        }
        return
      }
      case 'item/completed': {
        const item = params.item as Record<string, unknown> | undefined
        if (!item) return
        switch (item.type) {
          case 'agentMessage': {
            const id = String(item.id ?? '')
            const text = typeof item.text === 'string' && item.text ? item.text : (deltas.get(id) ?? '')
            deltas.delete(id)
            if (text) await this.runtime.postEvent({ t: 'text', text })
            return
          }
          case 'reasoning': {
            const summary = Array.isArray(item.summary) ? item.summary.join('\n') : item.summary
            if (typeof summary === 'string' && summary) {
              await this.runtime.postEvent({ t: 'text', text: summary, thinking: true })
            }
            return
          }
          case 'commandExecution': {
            const failed = item.status === 'failed' || (typeof item.exitCode === 'number' && item.exitCode !== 0)
            await this.runtime.postEvent({
              t: 'tool-call-end',
              call: String(item.id ?? ''),
              status: failed ? 'error' : 'ok',
              result: {
                exitCode: item.exitCode,
                output: typeof item.aggregatedOutput === 'string' ? item.aggregatedOutput.slice(0, 4_000) : undefined,
              },
            })
            return
          }
          case 'fileChange': {
            const changes = Array.isArray(item.changes) ? item.changes : []
            for (const change of changes) {
              const c = change as Record<string, unknown>
              await this.runtime.postEvent({
                t: 'file',
                path: String(c.path ?? ''),
                kind: typeof c.kind === 'string' ? c.kind : undefined,
              })
            }
            return
          }
          default:
            return
        }
      }
      case 'error': {
        const message = (params as { message?: string }).message ?? JSON.stringify(params).slice(0, 300)
        await this.runtime.postEvent({ t: 'service', kind: 'error', text: String(message) })
        return
      }
      default:
        return
    }
  }

  private async decideApproval(tool: string, params: Record<string, unknown>): Promise<string> {
    const hit = this.checkHardLimits(tool, params)
    if (hit) {
      await this.runtime.postEvent({
        t: 'service',
        kind: 'hard-limit',
        text: `硬性限制已拦截 ${tool === 'shell' ? '命令' : '文件改动'}（规则: ${hit.rule}）`,
      })
      return 'decline'
    }
    if (this.policy.autoApprove) return 'accept'
    const id = String(params.approvalId ?? params.itemId ?? Date.now())
    const answer = await this.runtime.requestPermission(id, tool, {
      command: params.command,
      cwd: params.cwd,
      reason: params.reason,
    })
    if (answer.approved) {
      return answer.decision === 'approved_for_session' ? 'acceptForSession' : 'accept'
    }
    return answer.decision === 'abort' ? 'cancel' : 'decline'
  }

  private checkHardLimits(
    tool: string,
    params: Record<string, unknown>,
  ): { rule: string } | null {
    if (this.hardLimits.isEmpty) return null
    if (tool === 'shell') {
      const command = Array.isArray(params.command) ? params.command.join(' ') : String(params.command ?? '')
      return this.hardLimits.matchCommand(command)
    }
    // 文件改动：requestApproval 只带 grantRoot/reason，路径从 item 事件里拿不到时退回 grantRoot
    const target = String(params.grantRoot ?? params.path ?? '')
    return target ? this.hardLimits.matchPath(target) : null
  }
}

export class CodexManager {
  private readonly runners = new Map<string, CodexSessionRunner>()

  constructor(private readonly context: RunnerContext) {}

  async spawn(params: SpawnParams) {
    try {
      const runner = await CodexSessionRunner.spawn(this.context, params)
      runner.onClosed = (sessionId) => this.runners.delete(sessionId)
      this.runners.set(runner.sessionId, runner)
      return { type: 'success' as const, sessionId: runner.sessionId }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      log(`spawn 失败: ${message}`)
      return { type: 'error' as const, errorMessage: message }
    }
  }

  async stopSession(sessionId: string): Promise<void> {
    const runner = this.runners.get(sessionId)
    if (runner) await runner.stop()
  }

  async stopAll(): Promise<void> {
    await Promise.allSettled([...this.runners.values()].map((runner) => runner.stop()))
  }
}
