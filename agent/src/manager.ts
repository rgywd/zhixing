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
  /** P2-2 完整闭环前默认关闭，避免 App 把半成品 Claude 通道展示为可用 */
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
          errorMessage: 'Claude P2 尚处于联调阶段；开发机需显式设置 ZHIXING_ENABLE_CLAUDE_P2=1',
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
