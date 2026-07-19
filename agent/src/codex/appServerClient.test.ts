import { describe, expect, it } from 'vitest'
import { buildThreadParams } from './appServerClient.js'

describe('buildThreadParams', () => {
  it('uses the selected permissions profile instead of conflicting legacy sandbox', () => {
    expect(buildThreadParams({
      cwd: 'C:\\repo',
      approvalPolicy: 'on-request',
      sandbox: 'workspace-write',
      permissions: ':workspace',
      model: 'gpt-5.4',
      reasoningEffort: 'max',
      serviceTier: 'priority',
    })).toEqual({
      cwd: 'C:\\repo',
      approvalPolicy: 'on-request',
      permissions: ':workspace',
      model: 'gpt-5.4',
      config: { model_reasoning_effort: 'max' },
      serviceTier: 'priority',
    })
  })

  it('keeps legacy sandbox when no permissions profile is selected', () => {
    expect(buildThreadParams({ sandbox: 'workspace-write' }, 'thread_1')).toEqual({
      threadId: 'thread_1',
      sandbox: 'workspace-write',
    })
  })
})
