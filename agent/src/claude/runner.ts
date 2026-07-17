import { randomUUID } from 'node:crypto'
import { hostname } from 'node:os'
import { HappyApi } from '../api.js'
import { log } from '../daemon.js'
import { SessionRuntime } from '../session.js'
import type { Credentials, SpawnParams } from '../types.js'
import {
  ClaudeCliProcess,
  ClaudeProcessInterruptedError,
  type ClaudePermissionMode,
  type ClaudeProcessPort,
  normalizeClaudePermissionMode,
} from './cli.js'

export interface ClaudeRunnerContext {
  serverUrl: string
  clientId: string
  machineId: string
  credentials: Credentials
}

interface LockedClaudeConfig {
  permissionMode: ClaudePermissionMode
  model?: string
  effort?: string
  disallowedTools: string[]
}

type ProcessFactory = () => ClaudeProcessPort

export class ClaudeSessionRunner {
  private runtime!: SessionRuntime
  private claudeSessionId: string
  private hasTranscript: boolean
  private config: LockedClaudeConfig | null = null
  private readonly queue: Array<{ text: string; meta: Record<string, unknown> }> = []
  private running = false
  private activeProcess: ClaudeProcessPort | null = null
  onClosed: (sessionId: string) => void = () => {}

  private constructor(
    private readonly context: ClaudeRunnerContext,
    private readonly params: SpawnParams,
    private readonly processFactory: ProcessFactory,
  ) {
    this.claudeSessionId = params.resumeClaudeSessionId ?? randomUUID()
    this.hasTranscript = Boolean(params.resumeClaudeSessionId)
  }

  get sessionId(): string {
    return this.runtime.sessionId
  }

  static async spawn(
    context: ClaudeRunnerContext,
    params: SpawnParams,
    processFactory: ProcessFactory = () => new ClaudeCliProcess(),
  ): Promise<ClaudeSessionRunner> {
    const runner = new ClaudeSessionRunner(context, params, processFactory)
    const api = new HappyApi(context.serverUrl, context.clientId)
    const session = await api.createSession(context.credentials, {
      path: params.directory,
      host: hostname(),
      machineId: context.machineId,
      flavor: 'claude',
      claudeSessionId: runner.claudeSessionId,
      startedBy: 'daemon',
      startedFromDaemon: true,
      permissionMode: normalizeClaudePermissionMode(params.permissionMode),
      pagerMode: true,
    })
    runner.runtime = new SessionRuntime({
      serverUrl: context.serverUrl,
      clientId: context.clientId,
      credentials: context.credentials,
      session,
      onUserMessage: (text, meta) => runner.enqueue(text, meta),
      onAbort: () => runner.interrupt(),
    })
    runner.runtime.connect()
    log(`Claude 会话已建立: session=${session.id} transcript=${runner.claudeSessionId}`)
    return runner
  }

  async stop(): Promise<void> {
    this.interrupt()
    await this.runtime.close('cancelled')
    this.onClosed(this.sessionId)
  }

  interrupt(): void {
    this.activeProcess?.interrupt()
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
      while ((next = this.queue.shift())) await this.runTurn(next.text, next.meta)
    } finally {
      this.running = false
    }
  }

  private lockConfig(meta: Record<string, unknown>): LockedClaudeConfig {
    if (this.config) return this.config
    const environmentEffort = this.params.environmentVariables?.CLAUDE_CODE_EFFORT_LEVEL
    const effort = stringValue(meta.reasoningEffort ?? meta.effortLevel) ?? this.params.effortLevel ?? environmentEffort
    this.config = {
      permissionMode: normalizeClaudePermissionMode(meta.permissionMode ?? this.params.permissionMode),
      model: stringValue(meta.model) ?? this.params.modelMode,
      effort,
      disallowedTools: normalizeStringList(meta.disallowedTools),
    }
    return this.config
  }

  private async runTurn(text: string, meta: Record<string, unknown>): Promise<void> {
    const config = this.lockConfig(meta)
    const requestedMode = meta.permissionMode === undefined ? config.permissionMode : normalizeClaudePermissionMode(meta.permissionMode)
    if (requestedMode !== config.permissionMode) {
      await this.runtime.postEvent({
        t: 'service',
        kind: 'policy-locked',
        text: 'Claude 会话权限档已在创建时锁定；如需切换，请新建会话。',
      })
    }
    const process = this.processFactory()
    this.activeProcess = process
    this.runtime.beginTurn()
    await this.runtime.postEvent({ t: 'turn-start' })
    try {
      const result = await process.run({
        cwd: this.params.directory,
        prompt: text,
        sessionId: this.claudeSessionId,
        resume: this.hasTranscript,
        permissionMode: config.permissionMode,
        model: config.model,
        effort: config.effort,
        disallowedTools: config.disallowedTools,
      })
      if (result.sessionId) this.claudeSessionId = result.sessionId
      this.hasTranscript = true
      if (result.result) await this.runtime.postEvent({ t: 'text', text: result.result })
      await this.runtime.postEvent({
        t: 'turn-end',
        status: result.isError ? 'failed' : 'completed',
      })
    } catch (error) {
      const interrupted = error instanceof ClaudeProcessInterruptedError
      if (!interrupted) {
        await this.runtime.postEvent({
          t: 'service',
          kind: 'error',
          text: `Claude 执行失败: ${error instanceof Error ? error.message : String(error)}`,
        })
      }
      await this.runtime.postEvent({ t: 'turn-end', status: interrupted ? 'cancelled' : 'failed' })
    } finally {
      this.runtime.endTurn()
      this.activeProcess = null
    }
  }
}

export class ClaudeManager {
  private readonly runners = new Map<string, ClaudeSessionRunner>()

  constructor(private readonly context: ClaudeRunnerContext) {}

  async spawn(params: SpawnParams) {
    try {
      const runner = await ClaudeSessionRunner.spawn(this.context, params)
      runner.onClosed = (sessionId) => this.runners.delete(sessionId)
      this.runners.set(runner.sessionId, runner)
      return { type: 'success' as const, sessionId: runner.sessionId }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      log(`Claude spawn 失败: ${message}`)
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

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function normalizeStringList(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === 'string' && Boolean(item.trim()))
  if (typeof value === 'string') return value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)
  return []
}
