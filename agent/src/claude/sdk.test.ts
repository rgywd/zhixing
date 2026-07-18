import { describe, expect, it, vi } from 'vitest'
import type { ClaudeInvocation } from './cli.js'
import { resolveSdkPermission } from './sdk.js'

function invocation(onPermission?: ClaudeInvocation['onPermission']): ClaudeInvocation {
  return {
    cwd: '/repo',
    prompt: 'work',
    sessionId: 'sid',
    resume: false,
    permissionMode: 'default',
    allowedTools: ['mcp__zhixing__report'],
    onPermission,
  }
}

describe('Claude Agent SDK permission mapping', () => {
  it('电话工具本机自动放行，不制造手机审批噪音', async () => {
    const callback = vi.fn(async () => ({ approved: false }))
    await expect(resolveSdkPermission(invocation(callback), 'mcp__zhixing__report', { text: '进展' })).resolves.toEqual({
      behavior: 'allow',
    })
    expect(callback).not.toHaveBeenCalled()
  })

  it('其他工具严格使用远程决定，缺少通道时拒绝', async () => {
    await expect(
      resolveSdkPermission(invocation(async () => ({ approved: false, message: '手机拒绝' })), 'Bash', {
        command: 'git push',
      }),
    ).resolves.toEqual({ behavior: 'deny', message: '手机拒绝' })
    await expect(resolveSdkPermission(invocation(), 'Write', { file_path: 'x' })).resolves.toMatchObject({
      behavior: 'deny',
    })
  })
})
