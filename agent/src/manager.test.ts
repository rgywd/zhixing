import { describe, expect, it, vi } from 'vitest'
import { AgentManager } from './manager.js'
import type { SpawnParams, SpawnResult } from './types.js'

function fakeManager(sessionId: string) {
  return {
    spawn: vi.fn(async (_params: SpawnParams): Promise<SpawnResult> => ({ type: 'success', sessionId })),
    stopSession: vi.fn(async () => {}),
    stopAll: vi.fn(async () => {}),
  }
}

describe('AgentManager', () => {
  it('按 agent 路由 Codex 与 Claude，新旧缺省仍走 Codex', async () => {
    const codex = fakeManager('codex-session')
    const claude = fakeManager('claude-session')
    const manager = new AgentManager(
      { serverUrl: 'https://relay', clientId: 'test', machineId: 'machine', credentials: { token: 't', secret: 's' } },
      { codex, claude },
    )
    await expect(manager.spawn({ directory: '/repo', agent: 'claude' })).resolves.toMatchObject({ sessionId: 'claude-session' })
    await expect(manager.spawn({ directory: '/repo' })).resolves.toMatchObject({ sessionId: 'codex-session' })
    expect(claude.spawn).toHaveBeenCalledTimes(1)
    expect(codex.spawn).toHaveBeenCalledTimes(1)
  })

  it('停止与关机同时清理两类 runner', async () => {
    const codex = fakeManager('codex-session')
    const claude = fakeManager('claude-session')
    const manager = new AgentManager(
      { serverUrl: 'https://relay', clientId: 'test', machineId: 'machine', credentials: { token: 't', secret: 's' } },
      { codex, claude },
    )
    await manager.stopSession('session')
    await manager.shutdown()
    expect(codex.stopSession).toHaveBeenCalledWith('session')
    expect(claude.stopSession).toHaveBeenCalledWith('session')
    expect(codex.stopAll).toHaveBeenCalledOnce()
    expect(claude.stopAll).toHaveBeenCalledOnce()
  })
})
