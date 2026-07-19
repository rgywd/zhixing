import { describe, expect, it } from 'vitest'
import { mkdir, mkdtemp, readFile, rm, stat, utimes, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { CodexThread } from '../codex/protocol.js'
import type { WirePayload } from './relayClient.js'
import {
  safeAttachmentId,
  safeFileName,
  safeMime,
  normalizeRuntimePath,
  WireCodexRuntimeBridge,
  type RuntimeCodexClient,
  type RuntimeRelay,
} from './runtimeBridge.js'

describe('WireCodexRuntimeBridge', () => {
  it('normalizes remote Windows and POSIX paths without applying the host path flavor', () => {
    expect(normalizeRuntimePath('C:\\skills\\review\\..\\review\\SKILL.md'))
      .toBe('C:\\skills\\review\\SKILL.md')
    expect(normalizeRuntimePath('/opt/skills/review/../review/SKILL.md'))
      .toBe('/opt/skills/review/SKILL.md')
  })

  it('executes the shared Android turn-start contract fixture end to end', async () => {
    const body = JSON.parse(await readFile(
      new URL('../../../docs/zhixing/fixtures/runtime-command-turn-start.json', import.meta.url),
      'utf8',
    )) as Record<string, unknown>
    const relay = new FakeRelay([
      command('request_catalog', 'runtime.catalog', { cwd: 'C:\\repo' }),
      command('android_fixture', 'turn.start', body),
    ])
    const codex = new FakeCodexClient()
    const bridge = new WireCodexRuntimeBridge(relay, { machineId: 'machine_1', clientFactory: () => codex })

    await bridge.pollOnce()

    expect(codex.resumed).toEqual(['thread_1'])
    expect(codex.startedTurns).toEqual([expect.objectContaining({
      threadId: 'thread_1',
      model: 'gpt-5.4',
      effort: 'max',
      serviceTier: 'priority',
      permissions: ':workspace',
      input: [
        { type: 'text', text: '实现 #49' },
        { type: 'skill', name: 'review', path: 'C:\\skills\\review\\SKILL.md' },
      ],
    })])
    expect(relay.published.find((item) => item.requestId === 'android_fixture'))
      .toMatchObject({ body: { ok: true, command: 'turn.start', result: { threadId: 'thread_1' } } })
  })

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
      command('request_catalog', 'runtime.catalog', { cwd: 'C:\\repo' }),
      command('request_resume', 'turn.start', {
        threadId: 'thread_1',
        cwd: 'C:\\repo',
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
      .toMatchObject({ body: { threadId: 'thread_1', itemId: 'item_1', text: '已完成', payload: { type: 'agentMessage' } } })

    codex.emit('item/reasoning/summaryTextDelta', { itemId: 'reason_1', delta: '检查状态' })
    codex.emit('item/commandExecution/outputDelta', { itemId: 'tool_1', delta: 'passed' })
    await tick()
    expect(relay.published.find((item) => (item.body as { itemId?: string }).itemId === 'reason_1'))
      .toMatchObject({ body: { itemType: 'reasoning', payload: { summary: ['检查状态'] } } })
    expect(relay.published.find((item) => (item.body as { itemId?: string }).itemId === 'tool_1'))
      .toMatchObject({ body: { itemType: 'commandExecution', payload: { aggregatedOutput: 'passed' } } })
  })

  it('streams plan, file, MCP progress, and model reroute notifications without flattening them away', async () => {
    const relay = new FakeRelay([
      command('request_catalog', 'runtime.catalog', { cwd: 'C:\\repo' }),
      command('request_resume', 'turn.start', {
        threadId: 'thread_1', cwd: 'C:\\repo', text: '继续', confirmedUnknown: true,
      }),
    ])
    const codex = new FakeCodexClient()
    const bridge = new WireCodexRuntimeBridge(relay, { machineId: 'machine_1', clientFactory: () => codex })
    await bridge.pollOnce()

    codex.emit('turn/started', { turn: { id: 'turn_1' } })
    codex.emit('item/plan/delta', { turnId: 'turn_1', itemId: 'plan_1', delta: '先检查' })
    codex.emit('turn/plan/updated', {
      turnId: 'turn_1',
      explanation: '执行计划',
      plan: [{ step: '检查', status: 'in_progress' }, { step: '验证', status: 'pending' }],
    })
    codex.emit('item/fileChange/patchUpdated', {
      turnId: 'turn_1', itemId: 'file_1', changes: [{ path: 'app.kt', kind: 'update' }],
    })
    codex.emit('item/fileChange/outputDelta', { turnId: 'turn_1', itemId: 'file_1', delta: 'patched' })
    codex.emit('item/mcpToolCall/progress', {
      turnId: 'turn_1', itemId: 'mcp_1', message: '正在读取 GitHub issue',
    })
    codex.emit('model/rerouted', {
      turnId: 'turn_1', fromModel: 'gpt-5.4', toModel: 'gpt-5.4-mini', reason: '容量调度',
    })
    await tick()

    expect(relay.published.find((item) => (item.body as { itemId?: string }).itemId === 'plan_1'))
      .toMatchObject({ body: { itemType: 'plan', text: '先检查', status: 'inProgress' } })
    expect(relay.published.find((item) => (item.body as { itemId?: string }).itemId === 'turn_plan_turn_1'))
      .toMatchObject({ body: { itemType: 'plan', payload: { explanation: '执行计划' } } })
    expect(relay.published.filter((item) => (item.body as { itemId?: string }).itemId === 'file_1').at(-1))
      .toMatchObject({ body: { itemType: 'fileChange', payload: { output: 'patched' } } })
    expect(relay.published.find((item) => (item.body as { itemId?: string }).itemId === 'mcp_1'))
      .toMatchObject({ body: { itemType: 'mcpToolCall', text: '正在读取 GitHub issue' } })
    expect(relay.published.find((item) => (item.body as { type?: string }).type === 'thread.settings'
      && (item.body as { model?: string }).model === 'gpt-5.4-mini'))
      .toMatchObject({ body: { payload: { reroutedFrom: 'gpt-5.4', reason: '容量调度' } } })
  })

  it('discovers runtime options and preserves structured turn parameters', async () => {
    const uploadDirectory = await mkdtemp(join(tmpdir(), 'zhixing-options-'))
    try {
      const content = Buffer.from('image-bytes')
      const hash = createHash('sha256').update(content).digest('hex')
      const uploadedPath = join(uploadDirectory, 'upload_ok_1234', 'bug.png')
      const relay = new FakeRelay([
        command('request_catalog', 'runtime.catalog', { cwd: 'C:\\repo' }),
        command('upload_ok', 'attachment.upload', {
          attachmentId: 'upload_ok_1234', fileName: 'bug.png', mime: 'image/png', chunkIndex: 0, chunkCount: 1,
          contentBase64: content.toString('base64'), sha256: hash,
        }),
        command('request_turn', 'turn.start', {
          threadId: 'thread_1',
          cwd: 'C:\\repo',
          confirmedUnknown: true,
          input: [
            { type: 'text', text: '看图' },
            { type: 'localImage', path: uploadedPath },
            { type: 'skill', name: 'review', path: 'C:\\skills\\review\\SKILL.md' },
          ],
          model: 'gpt-5.4',
          effort: 'max',
          serviceTier: 'priority',
          permissions: ':workspace',
        }),
      ])
      const codex = new FakeCodexClient()
      const bridge = new WireCodexRuntimeBridge(relay, {
        machineId: 'machine_1',
        clientFactory: () => codex,
        uploadDirectory,
      })

      await bridge.pollOnce()

      expect(relay.published.find((item) => item.type === 'runtime.catalog')).toMatchObject({
        body: {
          models: [{ id: 'gpt-5.4' }],
          permissionProfiles: [{ id: ':workspace' }],
          skills: [{ name: 'review' }],
          plugins: [{ name: 'github' }],
          apps: [{ id: 'drive' }],
          capabilities: { plugins: { available: true }, apps: { available: true } },
        },
      })
      expect(codex.startedTurns).toEqual([expect.objectContaining({
        model: 'gpt-5.4',
        effort: 'max',
        serviceTier: 'priority',
        permissions: ':workspace',
        input: expect.arrayContaining([{ type: 'localImage', path: uploadedPath }]),
      })])
    } finally {
      await rm(uploadDirectory, { recursive: true, force: true })
    }
  })

  it('chunks large thread history instead of exceeding the relay envelope', async () => {
    const relay = new FakeRelay([command('request_detail', 'thread.detail', { threadId: 'thread_1' })])
    const codex = new FakeCodexClient()
    codex.detailText = 'x'.repeat(800 * 1024)
    const bridge = new WireCodexRuntimeBridge(relay, { machineId: 'machine_1', clientFactory: () => codex })

    await bridge.pollOnce()

    const chunks = relay.published.filter((item) => item.type === 'thread.detail.chunk')
    expect(chunks.length).toBeGreaterThan(1)
    expect(relay.published.some((item) => item.type === 'thread.detail')).toBe(false)
    expect(chunks.map((item) => (item.body as { chunkIndex: number }).chunkIndex)).toEqual(
      Array.from({ length: chunks.length }, (_, index) => index),
    )
    const revision = (chunks[0]!.body as { revision: number }).revision
    expect(revision).toBeGreaterThan(0)
    expect(relay.published.find((item) => item.type === 'command.result'))
      .toMatchObject({ body: { ok: true, result: { threadId: 'thread_1', revision } } })
  })

  it('bridges Codex request_user_input through the existing inline ask-user tool', async () => {
    const relay = new FakeRelay([
      command('request_catalog', 'runtime.catalog', { cwd: 'C:\\repo' }),
      command('request_turn', 'turn.start', {
        threadId: 'thread_1', cwd: 'C:\\repo', text: '继续', confirmedUnknown: true,
      }),
    ])
    const codex = new FakeCodexClient()
    const bridge = new WireCodexRuntimeBridge(relay, { machineId: 'machine_1', clientFactory: () => codex })
    await bridge.pollOnce()

    const pendingAnswer = codex.request('item/tool/requestUserInput', {
      itemId: 'ask_1',
      turnId: 'turn_pending',
      questions: [{
        id: 'choice',
        header: '方案',
        question: '选择实现方案',
        options: [{ label: 'A', description: '方案 A' }, { label: 'B', description: '方案 B' }],
      }],
    })
    await tick()
    expect(relay.published.find((item) => (item.body as { itemId?: string }).itemId === 'ask_1'))
      .toMatchObject({ body: { itemType: 'dynamicToolCall', payload: { tool: 'ask_user' } } })
    expect(relay.published.find((item) => (item.body as { kind?: string }).kind === 'user_input'))
      .toMatchObject({ body: { payload: { itemId: 'ask_1' } } })

    relay.enqueue(command('answer_1', 'interaction.resolve', {
      threadId: 'thread_1',
      approvalId: 'input_ask_1',
      answer: '{"answers":{"choice":"A"}}',
    }))
    await bridge.pollOnce()

    await expect(pendingAnswer).resolves.toEqual({ answers: { choice: { answers: ['A'] } } })
  })

  it('maps accept decline and cancel decisions and can cancel request_user_input', async () => {
    const relay = new FakeRelay([
      command('request_catalog', 'runtime.catalog', { cwd: 'C:\\repo' }),
      command('request_turn', 'turn.start', {
        threadId: 'thread_1', cwd: 'C:\\repo', text: '继续', confirmedUnknown: true,
      }),
    ])
    const codex = new FakeCodexClient()
    const bridge = new WireCodexRuntimeBridge(relay, { machineId: 'machine_1', clientFactory: () => codex })
    await bridge.pollOnce()

    const pendingInput = codex.request('item/tool/requestUserInput', {
      itemId: 'ask_cancel', turnId: 'turn_pending',
      questions: [{ id: 'choice', question: '是否继续？', options: [] }],
    })
    const pendingApproval = codex.request('item/commandExecution/requestApproval', {
      approvalId: 'approval_cancel', itemId: 'command_1', command: 'npm test',
    })
    await tick()
    relay.enqueue(command('cancel_input', 'interaction.resolve', {
      threadId: 'thread_1', approvalId: 'input_ask_cancel', decision: 'cancel',
    }))
    relay.enqueue(command('cancel_approval', 'approval.resolve', {
      threadId: 'thread_1', approvalId: 'approval_cancel', decision: 'cancel',
    }))
    await bridge.pollOnce()

    await expect(pendingInput).resolves.toEqual({ answers: {} })
    await expect(pendingApproval).resolves.toEqual({ decision: 'cancel' })

    for (const decision of ['accept', 'decline'] as const) {
      const approvalId = `approval_${decision}`
      const pending = codex.request('item/commandExecution/requestApproval', {
        approvalId, itemId: `command_${decision}`, command: 'npm test',
      })
      await tick()
      relay.enqueue(command(`resolve_${decision}`, 'approval.resolve', {
        threadId: 'thread_1', approvalId, decision,
      }))
      await bridge.pollOnce()
      await expect(pending).resolves.toEqual({ decision })
    }

    expect(relay.published.filter((item) => (item.body as { type?: string }).type === 'approval.resolved'))
      .toEqual(expect.arrayContaining([
        expect.objectContaining({ body: expect.objectContaining({ approvalId: 'input_ask_cancel', decision: 'cancel' }) }),
        expect.objectContaining({ body: expect.objectContaining({ approvalId: 'approval_cancel', decision: 'cancel' }) }),
        expect.objectContaining({ body: expect.objectContaining({ approvalId: 'approval_accept', decision: 'accept' }) }),
        expect.objectContaining({ body: expect.objectContaining({ approvalId: 'approval_decline', decision: 'decline' }) }),
      ]))
  })

  it('rejects stale catalog values and local paths without an upload receipt', async () => {
    const relay = new FakeRelay([
      command('request_catalog', 'runtime.catalog', { cwd: 'C:\\repo' }),
      command('unsupported_model', 'turn.start', {
        threadId: 'thread_bad_model', cwd: 'C:\\repo', text: '继续', confirmedUnknown: true, model: 'unknown-model',
      }),
      command('unreceipted_path', 'turn.start', {
        threadId: 'thread_bad_path', cwd: 'C:\\repo', confirmedUnknown: true, model: 'gpt-5.4',
        input: [{ type: 'localImage', path: 'C:\\temp\\not-uploaded.png' }],
      }),
    ])
    const bridge = new WireCodexRuntimeBridge(relay, {
      machineId: 'machine_1', clientFactory: () => new FakeCodexClient(),
    })
    await bridge.pollOnce()

    expect(relay.published.find((item) => item.requestId === 'unsupported_model'))
      .toMatchObject({ body: { ok: false, error: '所选模型不在当前 Codex catalog 中' } })
    expect(relay.published.find((item) => item.requestId === 'unreceipted_path'))
      .toMatchObject({ body: { ok: false, error: '附件路径不是有效的上传回执' } })
  })

  it('reports plugin and app catalog failures explicitly instead of pretending the lists are empty', async () => {
    const relay = new FakeRelay([command('request_catalog', 'runtime.catalog', { cwd: 'C:\\repo' })])
    const codex = new FakeCodexClient()
    codex.pluginError = 'plugin endpoint unavailable'
    codex.appError = 'app endpoint unavailable'
    const bridge = new WireCodexRuntimeBridge(relay, { machineId: 'machine_1', clientFactory: () => codex })
    await bridge.pollOnce()

    expect(relay.published.find((item) => item.type === 'runtime.catalog')).toMatchObject({
      body: {
        plugins: [],
        apps: [],
        capabilities: {
          plugins: { available: false, error: 'plugin endpoint unavailable' },
          apps: { available: false, error: 'app endpoint unavailable' },
        },
      },
    })
  })

  it('rejects attachment path traversal and malformed MIME metadata', () => {
    expect(() => safeAttachmentId('../../escape')).toThrow('附件 ID 无效')
    expect(safeAttachmentId('12345678_safe')).toBe('12345678_safe')
    expect(safeFileName('../../bug screenshot.png')).toBe('bug_screenshot.png')
    expect(() => safeMime('not-a-mime')).toThrow('附件 MIME 无效')
    expect(safeMime(undefined)).toBe('application/octet-stream')
  })

  it('uploads attachments with hash and size checks and prunes expired files', async () => {
    const uploadDirectory = await mkdtemp(join(tmpdir(), 'zhixing-upload-'))
    try {
      const now = Date.now()
      const expiredDirectory = join(uploadDirectory, 'expired_1234')
      await mkdir(expiredDirectory)
      await writeFile(join(expiredDirectory, 'old.txt'), 'expired')
      const expiredAt = new Date(now - 25 * 60 * 60 * 1_000)
      await utimes(expiredDirectory, expiredAt, expiredAt)

      const content = Buffer.from('image-bytes')
      const hash = createHash('sha256').update(content).digest('hex')
      const tooLarge = Buffer.alloc(512 * 1024 + 1)
      const relay = new FakeRelay([
        command('upload_bad_hash', 'attachment.upload', {
          attachmentId: 'bad_hash_1234', fileName: 'bug.png', mime: 'image/png', chunkIndex: 0, chunkCount: 1,
          contentBase64: content.toString('base64'), sha256: '0'.repeat(64),
        }),
        command('upload_too_large', 'attachment.upload', {
          attachmentId: 'too_large_1234', fileName: 'bug.png', mime: 'image/png', chunkIndex: 0, chunkCount: 1,
          contentBase64: tooLarge.toString('base64'), sha256: createHash('sha256').update(tooLarge).digest('hex'),
        }),
        command('upload_ok', 'attachment.upload', {
          attachmentId: 'upload_ok_1234', fileName: 'bug.png', mime: 'image/png', chunkIndex: 0, chunkCount: 1,
          contentBase64: content.toString('base64'), sha256: hash,
        }),
      ])
      const bridge = new WireCodexRuntimeBridge(relay, {
        machineId: 'machine_1',
        clientFactory: () => new FakeCodexClient(),
        uploadDirectory,
        now: () => now,
      })

      await bridge.pollOnce()

      expect(relay.published.find((item) => item.requestId === 'upload_bad_hash'))
        .toMatchObject({ body: { ok: false, error: '附件哈希校验失败' } })
      expect(relay.published.find((item) => item.requestId === 'upload_too_large'))
        .toMatchObject({ body: { ok: false, error: '附件分片超过 512 KiB' } })
      const uploaded = relay.published.find((item) => item.requestId === 'upload_ok')
      expect(uploaded).toMatchObject({ body: { ok: true, result: { complete: true, mime: 'image/png' } } })
      const localPath = ((uploaded?.body as { result?: { localPath?: string } }).result?.localPath)
      expect(localPath).toBe(join(uploadDirectory, 'upload_ok_1234', 'bug.png'))
      await expect(readFile(localPath!)).resolves.toEqual(content)
      await expect(stat(expiredDirectory)).rejects.toThrow()
    } finally {
      await rm(uploadDirectory, { recursive: true, force: true })
    }
  })

  it('downloads only local images referenced by the requested Codex thread', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'zhixing-download-'))
    try {
      const imagePath = join(directory, 'bug.png')
      await writeFile(imagePath, Buffer.from('image-bytes'))
      const relay = new FakeRelay([
        command('download_ok', 'attachment.download', { threadId: 'thread_1', path: imagePath, chunkIndex: 0 }),
        command('download_denied', 'attachment.download', { threadId: 'thread_1', path: join(directory, 'secret.txt'), chunkIndex: 0 }),
      ])
      const codex = new FakeCodexClient()
      codex.localImagePath = imagePath
      const bridge = new WireCodexRuntimeBridge(relay, { machineId: 'machine_1', clientFactory: () => codex })

      await bridge.pollOnce()

      expect(relay.published.find((item) => item.requestId === 'download_ok')).toMatchObject({
        body: { ok: true, result: { fileName: 'bug.png', mime: 'image/png', chunkCount: 1 } },
      })
      expect(relay.published.find((item) => item.requestId === 'download_denied')).toMatchObject({
        body: { ok: false, error: '附件路径不属于该 Codex 任务' },
      })
    } finally {
      await rm(directory, { recursive: true, force: true })
    }
  })

  it('publishes a new thread before its first turn and maps the user message as user content', async () => {
    const relay = new FakeRelay([
      command('request_catalog', 'runtime.catalog', { cwd: 'C:\\repo' }),
      command('request_start', 'thread.start', { cwd: 'C:\\repo', text: '开始工作' }),
    ])
    const codex = new FakeCodexClient()
    const bridge = new WireCodexRuntimeBridge(relay, {
      machineId: 'machine_1',
      clientFactory: () => codex,
    })

    await bridge.pollOnce()
    expect(relay.published.find((item) => (item.body as { type?: string }).type === 'thread.settings'))
      .toMatchObject({
        body: {
          threadId: 'thread_new',
          model: 'gpt-5.4',
          effort: 'max',
          serviceTier: 'priority',
          permissions: ':workspace',
        },
      })
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

  enqueue(payload: WirePayload) { this.payloads.push(payload) }

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
  readonly startedTurns: Parameters<RuntimeCodexClient['startTurn']>[0][] = []
  detailText = ''
  localImagePath = ''
  pluginError = ''
  appError = ''
  confirmedSettings = {
    model: 'gpt-5.4',
    reasoningEffort: 'max',
    serviceTier: 'priority',
    activePermissionProfile: { id: ':workspace' },
  }
  private notification: (method: string, params: unknown) => void = () => {}
  private serverRequest: Parameters<RuntimeCodexClient['onServerRequest']>[0] = async () => ({})

  async start() {}
  stop() {}
  onNotification(handler: (method: string, params: unknown) => void) { this.notification = handler }
  onServerRequest(handler: Parameters<RuntimeCodexClient['onServerRequest']>[0]) { this.serverRequest = handler }
  request(method: string, params: unknown) { return this.serverRequest(method, params) }
  emit(method: string, params: unknown) { this.notification(method, params) }
  async startThread() { return { threadId: 'thread_new', raw: this.confirmedSettings } }
  async resumeThread(threadId: string) {
    this.resumed.push(threadId)
    return this.confirmedSettings
  }
  async forkThread() { return { threadId: 'thread_fork' } }
  async startTurn(options: Parameters<RuntimeCodexClient['startTurn']>[0]) {
    this.startedTurns.push(options)
    return { turnId: 'turn_pending' }
  }
  async steerTurn() {}
  async interruptTurn() {}
  async archiveThread(threadId: string) { this.archived.push(threadId) }
  async unarchiveThread() {}
  async deleteThread() {}
  async listModels() {
    return { data: [{
      id: 'gpt-5.4',
      inputModalities: ['text', 'image'],
      supportedReasoningEfforts: [{ reasoningEffort: 'max' }],
      serviceTiers: [{ id: 'priority' }],
    }] }
  }
  async listPermissionProfiles() { return { data: [{ id: ':workspace', allowed: true }] } }
  async listSkills() {
    return { data: [{ skills: [{ name: 'review', path: 'C:\\skills\\review\\SKILL.md', enabled: true }] }] }
  }
  async listPlugins() {
    if (this.pluginError) throw new Error(this.pluginError)
    return { marketplaces: [{ plugins: [{ name: 'github' }] }] }
  }
  async listApps() {
    if (this.appError) throw new Error(this.appError)
    return { data: [{ id: 'drive' }] }
  }
  async readThread(threadId: string, includeTurns = true): Promise<CodexThread> {
    this.reads.push({ threadId, includeTurns })
    const thread = rawThread(threadId)
    if (this.localImagePath) {
      thread.raw = {
        ...thread.raw,
        turns: [{ items: [{ type: 'userMessage', content: [{ type: 'localImage', path: this.localImagePath }] }] }],
      }
    }
    if (this.detailText) {
      thread.turns = [{
        id: 'turn_large',
        items: [{ id: 'item_large', type: 'agentMessage', text: this.detailText }],
        status: 'completed',
      }]
    }
    return thread
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
