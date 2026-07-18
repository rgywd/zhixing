import { describe, expect, it } from 'vitest'
import { buildClaudeArgs, normalizeClaudePermissionMode, parseClaudeResult } from './cli.js'

describe('Claude CLI invocation', () => {
  it('新会话固定 session id，普通档使用 default 权限', () => {
    expect(
      buildClaudeArgs({
        cwd: '/repo',
        prompt: '实现需求',
        sessionId: '00000000-0000-4000-8000-000000000001',
        resume: false,
        permissionMode: 'default',
        model: 'sonnet',
        effort: 'high',
        disallowedTools: ['Bash(git push --force*)', 'Read(./.env)'],
      }),
    ).toEqual([
      '-p',
      '实现需求',
      '--output-format',
      'json',
      '--session-id',
      '00000000-0000-4000-8000-000000000001',
      '--model',
      'sonnet',
      '--effort',
      'high',
      '--disallowedTools',
      'Bash(git push --force*)',
      'Read(./.env)',
      '--permission-mode',
      'default',
    ])
  })

  it('后续轮 resume 同一 transcript，完全访问显式 skip permissions', () => {
    const args = buildClaudeArgs({
      cwd: '/repo',
      prompt: '继续',
      sessionId: 'sid',
      resume: true,
      permissionMode: 'bypassPermissions',
    })
    expect(args).toContain('--resume')
    expect(args).not.toContain('--session-id')
    expect(args).toContain('--dangerously-skip-permissions')
    expect(args).not.toContain('--permission-mode')
  })

  it('注入本机 Hook 与 MCP 时仍让 prompt 紧跟 -p，密钥不进入参数', () => {
    const args = buildClaudeArgs({
      cwd: '/repo',
      prompt: '继续开发',
      sessionId: 'sid',
      resume: true,
      permissionMode: 'default',
      settings: { hooks: { PermissionRequest: [] } },
      mcpConfig: { mcpServers: { zhixing: { command: 'node', args: ['mcp.js'] } } },
      appendSystemPrompt: '通过知行汇报',
      allowedTools: ['mcp__zhixing__report'],
      environment: { ZHIXING_CLAUDE_BRIDGE_TOKEN: 'secret' },
    })
    expect(args.slice(0, 2)).toEqual(['-p', '继续开发'])
    expect(args).toContain('--settings')
    expect(args).toContain('--mcp-config')
    expect(args).toContain('--append-system-prompt')
    expect(args).toContain('--allowedTools')
    expect(args.join(' ')).not.toContain('secret')
  })

  it('未知权限模式降级为普通档', () => {
    expect(normalizeClaudePermissionMode('bypassPermissions')).toBe('bypassPermissions')
    expect(normalizeClaudePermissionMode('yolo')).toBe('bypassPermissions')
    expect(normalizeClaudePermissionMode('acceptEdits')).toBe('default')
    expect(normalizeClaudePermissionMode(undefined)).toBe('default')
  })

  it('解析 headless JSON 结果和错误状态', () => {
    expect(
      parseClaudeResult(
        JSON.stringify({
          type: 'result',
          subtype: 'success',
          is_error: false,
          result: '完成',
          session_id: 'sid',
          total_cost_usd: 0.01,
        }),
      ),
    ).toMatchObject({ sessionId: 'sid', result: '完成', isError: false, totalCostUsd: 0.01 })
    expect(parseClaudeResult('{"subtype":"error_during_execution","is_error":true,"result":"失败"}').isError).toBe(true)
    expect(() => parseClaudeResult('not-json')).toThrow('invalid JSON')
  })
})
