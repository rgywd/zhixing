import { spawn, type ChildProcess } from 'node:child_process'

const MAX_STDOUT_BYTES = 4 * 1024 * 1024
const MAX_STDERR_BYTES = 256 * 1024

export type ClaudePermissionMode = 'default' | 'bypassPermissions'

export interface ClaudeInvocation {
  cwd: string
  prompt: string
  sessionId: string
  resume: boolean
  permissionMode: ClaudePermissionMode
  model?: string
  effort?: string
  allowedTools?: string[]
  disallowedTools?: string[]
  environment?: Record<string, string>
  settings?: Record<string, unknown>
  mcpConfig?: Record<string, unknown>
  strictMcpConfig?: boolean
  appendSystemPrompt?: string
  onPermission?: (
    tool: string,
    input: Record<string, unknown>,
  ) => Promise<{ approved: boolean; message?: string }>
}

export interface ClaudeResult {
  sessionId?: string
  result: string
  isError: boolean
  subtype?: string
  usage?: unknown
  totalCostUsd?: number
}

export class ClaudeProcessInterruptedError extends Error {
  constructor() {
    super('Claude Code turn interrupted')
  }
}

export function normalizeClaudePermissionMode(mode: unknown): ClaudePermissionMode {
  return mode === 'bypassPermissions' || mode === 'yolo' ? 'bypassPermissions' : 'default'
}

export function buildClaudeArgs(invocation: ClaudeInvocation): string[] {
  // prompt 必须紧跟 -p。--disallowedTools 是 variadic，若把 prompt 放到命令末尾会被吞成规则。
  const args = ['-p', invocation.prompt, '--output-format', 'json']
  if (invocation.resume) {
    args.push('--resume', invocation.sessionId)
  } else {
    args.push('--session-id', invocation.sessionId)
  }
  if (invocation.model) args.push('--model', invocation.model)
  if (invocation.effort) args.push('--effort', invocation.effort)
  if (invocation.settings) args.push('--settings', JSON.stringify(invocation.settings))
  if (invocation.mcpConfig) args.push('--mcp-config', JSON.stringify(invocation.mcpConfig))
  if (invocation.strictMcpConfig) args.push('--strict-mcp-config')
  if (invocation.appendSystemPrompt) args.push('--append-system-prompt', invocation.appendSystemPrompt)
  if (invocation.allowedTools?.length) {
    args.push('--allowedTools', ...invocation.allowedTools)
  }
  if (invocation.disallowedTools?.length) {
    args.push('--disallowedTools', ...invocation.disallowedTools)
  }
  if (invocation.permissionMode === 'bypassPermissions') {
    args.push('--dangerously-skip-permissions')
  } else {
    args.push('--permission-mode', 'default')
  }
  return args
}

export function parseClaudeResult(stdout: string): ClaudeResult {
  const trimmed = stdout.trim()
  if (!trimmed) throw new Error('Claude Code returned empty output')
  let raw: Record<string, unknown>
  try {
    raw = JSON.parse(trimmed) as Record<string, unknown>
  } catch {
    throw new Error(`Claude Code returned invalid JSON: ${trimmed.slice(0, 300)}`)
  }
  return {
    sessionId: typeof raw.session_id === 'string' ? raw.session_id : undefined,
    result: typeof raw.result === 'string' ? raw.result : '',
    isError: raw.is_error === true || String(raw.subtype ?? '').startsWith('error'),
    subtype: typeof raw.subtype === 'string' ? raw.subtype : undefined,
    usage: raw.usage,
    totalCostUsd: typeof raw.total_cost_usd === 'number' ? raw.total_cost_usd : undefined,
  }
}

export interface ClaudeProcessPort {
  run(invocation: ClaudeInvocation): Promise<ClaudeResult>
  interrupt(): void
}

export class ClaudeCliProcess implements ClaudeProcessPort {
  private child: ChildProcess | null = null
  private interrupted = false

  constructor(private readonly binary = process.env.ZHIXING_CLAUDE_BIN ?? 'claude') {}

  run(invocation: ClaudeInvocation): Promise<ClaudeResult> {
    if (this.child) return Promise.reject(new Error('Claude Code process is already running'))
    this.interrupted = false
    return new Promise((resolve, reject) => {
      const child = spawn(this.binary, buildClaudeArgs(invocation), {
        cwd: invocation.cwd,
        env: { ...process.env, ...invocation.environment },
        stdio: ['ignore', 'pipe', 'pipe'],
        windowsHide: true,
      })
      this.child = child
      const stdout: Buffer[] = []
      const stderr: Buffer[] = []
      let stdoutBytes = 0
      let stderrBytes = 0
      let settled = false

      const finish = (error?: Error, result?: ClaudeResult) => {
        if (settled) return
        settled = true
        this.child = null
        if (error) reject(error)
        else resolve(result!)
      }

      child.stdout.on('data', (chunk: Buffer) => {
        stdoutBytes += chunk.length
        if (stdoutBytes > MAX_STDOUT_BYTES) {
          child.kill('SIGTERM')
          finish(new Error(`Claude Code output exceeded ${MAX_STDOUT_BYTES} bytes`))
        } else {
          stdout.push(chunk)
        }
      })
      child.stderr.on('data', (chunk: Buffer) => {
        if (stderrBytes >= MAX_STDERR_BYTES) return
        stderrBytes += chunk.length
        stderr.push(chunk.subarray(0, Math.max(0, MAX_STDERR_BYTES - (stderrBytes - chunk.length))))
      })
      child.once('error', (error) => finish(error))
      child.once('close', (code, signal) => {
        if (settled) return
        if (this.interrupted) return finish(new ClaudeProcessInterruptedError())
        const stderrText = Buffer.concat(stderr).toString('utf8').trim()
        if (code !== 0) {
          return finish(
            new Error(
              `Claude Code exited (code=${code}, signal=${signal ?? 'none'}): ${stderrText.slice(0, 1_000)}`,
            ),
          )
        }
        try {
          finish(undefined, parseClaudeResult(Buffer.concat(stdout).toString('utf8')))
        } catch (error) {
          finish(error instanceof Error ? error : new Error(String(error)))
        }
      })
    })
  }

  interrupt(): void {
    this.interrupted = true
    this.child?.kill('SIGTERM')
  }
}
