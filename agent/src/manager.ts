import { ClaudeManager } from './claude/runner.js'
import { CodexManager } from './codex/runner.js'
import type { Credentials, SpawnParams, SpawnResult } from './types.js'

interface SessionManager {
  spawn(params: SpawnParams): Promise<SpawnResult>
  stopSession(sessionId: string): Promise<void>
  stopAll(): Promise<void>
}

export interface AgentManagerContext {
  serverUrl: string
  clientId: string
  machineId: string
  credentials: Credentials
}

export class AgentManager {
  private readonly codex: SessionManager
  private readonly claude: SessionManager

  constructor(context: AgentManagerContext, managers?: { codex: SessionManager; claude: SessionManager }) {
    this.codex = managers?.codex ?? new CodexManager(context)
    this.claude = managers?.claude ?? new ClaudeManager(context)
  }

  spawn(params: SpawnParams): Promise<SpawnResult> {
    return (params.agent ?? 'codex') === 'claude' ? this.claude.spawn(params) : this.codex.spawn(params)
  }

  async stopSession(sessionId: string): Promise<void> {
    await Promise.all([this.codex.stopSession(sessionId), this.claude.stopSession(sessionId)])
  }

  async shutdown(): Promise<void> {
    await Promise.all([this.codex.stopAll(), this.claude.stopAll()])
  }
}
