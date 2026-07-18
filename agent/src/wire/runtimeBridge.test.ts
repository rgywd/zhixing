import { describe, expect, it } from 'vitest'
import type { CodexThread } from '../codex/protocol.js'
import type { WirePayload } from './relayClient.js'
import {
  WireCodexRuntimeBridge,
  type RuntimeCodexClient,
  type RuntimeRelay,
} from './runtimeBridge.js'

describe('WireCodexRuntimeBridge', () => {
  it('does not execute a dangerous duplicate request twice', async () => {
    const relay = new FakeRelay([
      command('request_archive', 'thread.archive', { threadId: 'thread_1' }),
      command('request_archive', 'thread.archive', { threadId: 'thread_1' }),
    ])
    const codex = new FakeCodexClient()
    const bridge = new WireCodexRuntimeBridge(relay, {
      machineId: 'machine_1',
      clientFactory: () => codex,
    })

    await bridge.pollOnce()

    expect(codex.archived).toEqual(['thread_1'])
    expect(relay.published.filter((item) => item.type === 'command.result')).toHaveLength(2)
  })

  it('requires explicit confirmation before taking over a Desktop thread', async () => {
    const relay = new FakeRelay([
      command('request_resume', 'turn.start', { threadId: 'thread_1', text: '继续' }),
      command('request_resume', 'turn.start', { threadId: 'thread_1', text: '继续' }),
    ])
    const codex = new FakeCodexClient()
    const bridge = new WireCodexRuntimeBridge(relay, {
      machineId: 'machine_1',
      clientFactory: () => codex,
    })

    await bridge.pollOnce()

    expect(codex.resumed).toEqual([])
    expect(relay.published.filter((item) => item.type === 'command.result')).toHaveLength(2)
    for (const result of relay.published.filter((item) => item.type === 'command.result')) {
      expect(result).toMatchObject({
        type: 'command.result',
        body: { ok: false, error: '该任务由桌面端创建，继续前需要用户确认接管' },
      })
    }
  })

  it('publishes agent deltas into the native runtime stream', async () => {
    const relay = new FakeRelay([
      command('request_resume', 'turn.start', {
        threadId: 'thread_1',
        text: '继续',
        confirmedUnknown: true,
      }),
    ])
    const codex = new FakeCodexClient()
    const bridge = new WireCodexRuntimeBridge(relay, {
      machineId: 'machine_1',
      clientFactory: () => codex,
    })

    await bridge.pollOnce()
    codex.emit('turn/started', { turn: { id: 'turn_1' } })
    codex.emit('item/agentMessage/delta', { itemId: 'item_1', delta: '已' })
    codex.emit('item/agentMessage/delta', { itemId: 'item_1', delta: '完成' })
    await tick()

    expect(relay.published.filter((item) => (item.body as { type?: string }).type === 'item.delta').at(-1))
      .toMatchObject({ body: { threadId: 'thread_1', itemId: 'item_1', text: '已完成' } })
  })

  it('publishes a new thread before its first turn and maps the user message as user content', async () => {
    const relay = new FakeRelay([
      command('request_start', 'thread.start', { cwd: 'C:\\repo', text: '开始工作' }),
    ])
    const codex = new FakeCodexClient()
    const bridge = new WireCodexRuntimeBridge(relay, {
      machineId: 'machine_1',
      clientFactory: () => codex,
    })

    await bridge.pollOnce()
    codex.emit('item/started', {
      item: { id: 'user_1', type: 'userMessage', content: [{ type: 'text', text: '开始工作' }] },
    })
    await tick()

    expect(relay.published.some((item) => item.type === 'thread.detail')).toBe(true)
    expect(codex.reads).toContainEqual({ threadId: 'thread_new', includeTurns: false })
    expect(relay.published.find((item) => (item.body as { itemId?: string }).itemId === 'user_1'))
      .toMatchObject({ body: { role: 'user', text: '开始工作' } })
  })
})

class FakeRelay implements RuntimeRelay {
  readonly published: Array<{ type: string; body: unknown; requestId: string | null }> = []
  private readonly requests = new Map<string, {
    state: 'inflight' | 'completed'
    updatedAt: number
    result?: unknown
    error?: string
  }>()

  constructor(private readonly payloads: WirePayload[]) {}

  async poll(): Promise<{ payloads: WirePayload[]; gapDetected: boolean }> {
    return { payloads: this.payloads.splice(0), gapDetected: false }
  }

  async publish(type: string, body: unknown, _stream: string, requestId: string | null = null): Promise<number> {
    this.published.push({ type, body, requestId })
    return 1
  }

  requestState(requestId: string) {
    return this.requests.get(requestId)
  }

  markRequest(requestId: string, state: 'inflight' | 'completed', result?: unknown, error?: string): void {
    this.requests.set(requestId, { state, updatedAt: Date.now(), result, error })
  }
}

class FakeCodexClient implements RuntimeCodexClient {
  readonly archived: string[] = []
  readonly resumed: string[] = []
  readonly reads: Array<{ threadId: string; includeTurns: boolean }> = []
  private notification: (method: string, params: unknown) => void = () => {}

  async start() {}
  stop() {}
  onNotification(handler: (method: string, params: unknown) => void) { this.notification = handler }
  onServerRequest() {}
  emit(method: string, params: unknown) { this.notification(method, params) }
  async startThread() { return { threadId: 'thread_new', raw: {} } }
  async resumeThread(threadId: string) { this.resumed.push(threadId) }
  async forkThread() { return { threadId: 'thread_fork' } }
  async startTurn() { return { turnId: 'turn_pending' } }
  async steerTurn() {}
  async interruptTurn() {}
  async archiveThread(threadId: string) { this.archived.push(threadId) }
  async unarchiveThread() {}
  async deleteThread() {}
  async readThread(threadId: string, includeTurns = true): Promise<CodexThread> {
    this.reads.push({ threadId, includeTurns })
    return rawThread(threadId)
  }
}

function command(
  requestId: string,
  name: string,
  body: Record<string, unknown>,
): WirePayload {
  return {
    type: 'runtime.command',
    schema: 1,
    requestId,
    sentAt: Date.now(),
    body: { command: name, machineId: 'machine_1', ...body },
  }
}

function rawThread(id: string): CodexThread {
  return {
    id,
    sessionId: `session_${id}`,
    forkedFromId: null,
    parentThreadId: null,
    preview: '',
    ephemeral: false,
    modelProvider: 'openai',
    createdAt: 1,
    updatedAt: 1,
    recencyAt: 1,
    status: { type: 'idle' },
    cwd: 'C:\\repo',
    cliVersion: '0.144.0',
    source: 'appServer',
    threadSource: null,
    agentNickname: null,
    agentRole: null,
    gitInfo: null,
    name: id,
    turns: [],
    raw: {},
  }
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0))
