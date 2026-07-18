import { existsSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import {
  query,
  type McpServerConfig,
  type Query,
  type SDKResultMessage,
} from '@anthropic-ai/claude-agent-sdk'
import {
  ClaudeProcessInterruptedError,
  type ClaudeInvocation,
  type ClaudeProcessPort,
  type ClaudeResult,
} from './cli.js'

export class ClaudeSdkProcess implements ClaudeProcessPort {
  private active: Query | null = null
  private interrupted = false

  async run(invocation: ClaudeInvocation): Promise<ClaudeResult> {
    if (this.active) throw new Error('Claude Code process is already running')
    this.interrupted = false
    const queryProcess = query({
      prompt: invocation.prompt,
      options: {
        cwd: invocation.cwd,
        sessionId: invocation.resume ? undefined : invocation.sessionId,
        resume: invocation.resume ? invocation.sessionId : undefined,
        permissionMode: invocation.permissionMode === 'bypassPermissions' ? 'bypassPermissions' : 'default',
        allowDangerouslySkipPermissions: invocation.permissionMode === 'bypassPermissions',
        model: invocation.model,
        effort: normalizeEffort(invocation.effort),
        disallowedTools: invocation.disallowedTools,
        mcpServers: extractMcpServers(invocation.mcpConfig),
        strictMcpConfig: invocation.strictMcpConfig,
        systemPrompt: invocation.appendSystemPrompt
          ? { type: 'preset', preset: 'claude_code', append: invocation.appendSystemPrompt }
          : { type: 'preset', preset: 'claude_code' },
        env: { ...process.env, ...invocation.environment },
        pathToClaudeCodeExecutable: resolveClaudeExecutable(),
        canUseTool:
          invocation.permissionMode === 'default' && invocation.onPermission
            ? (toolName, input) => resolveSdkPermission(invocation, toolName, input)
            : undefined,
      },
    })
    this.active = queryProcess
    let result: SDKResultMessage | undefined
    try {
      for await (const message of queryProcess) {
        if (message.type === 'result') result = message
      }
    } catch (error) {
      if (this.interrupted) throw new ClaudeProcessInterruptedError()
      throw error
    } finally {
      this.active = null
    }
    if (this.interrupted) throw new ClaudeProcessInterruptedError()
    if (!result) throw new Error('Claude Agent SDK returned no result message')
    return {
      sessionId: result.session_id,
      result: result.subtype === 'success' ? result.result : result.errors.join('\n'),
      isError: result.is_error || result.subtype !== 'success',
      subtype: result.subtype,
      usage: result.usage,
      totalCostUsd: result.total_cost_usd,
    }
  }

  interrupt(): void {
    this.interrupted = true
    this.active?.close()
  }
}

export async function resolveSdkPermission(
  invocation: ClaudeInvocation,
  toolName: string,
  input: Record<string, unknown>,
) {
  if (invocation.allowedTools?.includes(toolName)) return { behavior: 'allow' as const }
  if (!invocation.onPermission) return { behavior: 'deny' as const, message: '远程审批通道不可用。' }
  const answer = await invocation.onPermission(toolName, input)
  return answer.approved
    ? { behavior: 'allow' as const }
    : { behavior: 'deny' as const, message: answer.message || '用户拒绝了此操作。' }
}

function extractMcpServers(config?: Record<string, unknown>): Record<string, McpServerConfig> | undefined {
  const servers = config?.mcpServers
  return servers && typeof servers === 'object' ? (servers as Record<string, McpServerConfig>) : undefined
}

function normalizeEffort(value?: string): 'low' | 'medium' | 'high' | 'xhigh' | 'max' | undefined {
  return value === 'low' || value === 'medium' || value === 'high' || value === 'xhigh' || value === 'max'
    ? value
    : undefined
}

function resolveClaudeExecutable(): string | undefined {
  if (process.env.ZHIXING_CLAUDE_BIN) return process.env.ZHIXING_CLAUDE_BIN
  const candidate = join(homedir(), '.local', 'bin', process.platform === 'win32' ? 'claude.exe' : 'claude')
  return existsSync(candidate) ? candidate : undefined
}
