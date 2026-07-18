import { ClaudeManager } from './claude/runner.js'
import { CodexManager } from './codex/runner.js'
import { isClaudeP2Enabled } from './config.js'
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
  /** 默认启用；仅供紧急回滚时显式关闭 Claude 通道 */
  enableClaude?: boolean
}

export class AgentManager {
  private readonly codex: SessionManager
  private readonly claude: SessionManager
  private readonly claudeEnabled: boolean

  constructor(context: AgentManagerContext, managers?: { codex: SessionManager; claude: SessionManager }) {
    this.codex = managers?.codex ?? new CodexManager(context)
    this.claude = managers?.claude ?? new ClaudeManager(context)
    this.claudeEnabled = context.enableClaude ?? isClaudeP2Enabled()
  }

  spawn(params: SpawnParams): Promise<SpawnResult> {
    if ((params.agent ?? 'codex') === 'claude') {
      if (!this.claudeEnabled) {
        return Promise.resolve({
          type: 'error',
          errorMessage: 'Claude 通道已被开发机设置 ZHIXING_ENABLE_CLAUDE_P2=0 关闭',
        })
      }
      return this.claude.spawn(params)
    }
    return this.codex.spawn(params)
  }

  async stopSession(sessionId: string): Promise<void> {
    await Promise.all([this.codex.stopSession(sessionId), this.claude.stopSession(sessionId)])
  }

  async shutdown(): Promise<void> {
    await Promise.all([this.codex.stopAll(), this.claude.stopAll()])
  }
}
