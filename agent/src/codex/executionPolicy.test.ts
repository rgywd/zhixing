import { describe, expect, it } from 'vitest'
import { normalizeMode, resolveExecutionPolicy } from './executionPolicy.js'

describe('execution policy', () => {
  it('普通档 → untrusted + workspace-write，审批走手机', () => {
    expect(resolveExecutionPolicy('default')).toEqual({
      approvalPolicy: 'untrusted',
      sandbox: 'workspace-write',
      autoApprove: false,
    })
  })

  it('完全访问 → never + danger-full-access，自动通过', () => {
    expect(resolveExecutionPolicy('bypassPermissions')).toEqual({
      approvalPolicy: 'never',
      sandbox: 'danger-full-access',
      autoApprove: true,
    })
  })

  it('未知/历史模式一律落回普通档', () => {
    for (const mode of [undefined, 'plan', 'acceptEdits', 'read-only', 'safe-yolo', '']) {
      expect(resolveExecutionPolicy(mode).autoApprove).toBe(false)
    }
    expect(normalizeMode('yolo')).toBe('bypassPermissions')
    expect(normalizeMode('plan')).toBe('default')
  })
})
