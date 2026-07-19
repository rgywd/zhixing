import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { basename, extname, join, resolve } from 'node:path'
import { normalizeThreadDetail } from '../catalog/threadDetail.js'
import {
  CodexAppServerClient,
  type ServerRequestHandler,
  type ThreadStartOptions,
  type TurnStartOptions,
} from '../codex/appServerClient.js'
import type { CodexThread } from '../codex/protocol.js'
import { analyzeCodexCompatibility } from '../codex/compatibility.js'
import { generateCodexSchemaHash } from '../codex/schemaHash.js'
import { log } from '../daemon.js'
import type { WirePayload } from './relayClient.js'

export type RuntimeCommandName =
  | 'runtime.catalog'
  | 'attachment.upload'
  | 'attachment.download'
  | 'thread.detail'
  | 'thread.start'
  | 'thread.resume'
  | 'thread.fork'
  | 'thread.archive'
  | 'thread.unarchive'
  | 'thread.delete'
  | 'turn.start'
  | 'turn.steer'
  | 'turn.interrupt'
  | 'approval.resolve'
  | 'interaction.resolve'

export interface RuntimeCommand {
  command: RuntimeCommandName
  machineId: string
  threadId?: string
  cwd?: string
  text?: string
  input?: Array<Record<string, unknown>>
  confirmedUnknown?: boolean
  approvalId?: string
  decision?: 'accept' | 'decline' | 'cancel'
  answer?: string
  model?: string
  effort?: string
  approvalPolicy?: string
  sandbox?: string
  serviceTier?: string
  permissions?: string
  attachmentId?: string
  fileName?: string
  mime?: string
  chunkIndex?: number
  chunkCount?: number
  contentBase64?: string
  sha256?: string
  path?: string
}

interface RuntimeBinding {
  bindingId: string
  threadId: string
  client: RuntimeCodexClient
  activeTurnId: string | null
  deltas: Map<string, string>
  items: Map<string, Record<string, unknown>>
}

interface PendingApproval {
  resolve: (decision: string) => void
  threadId: string
}

interface PendingInteraction {
  resolve: (response: unknown) => void
  threadId: string
}

export interface RuntimeBridgeOptions {
  machineId: string
  clientFactory?: () => RuntimeCodexClient
  uploadDirectory?: string
  now?: () => number
}

export interface RuntimeCodexClient {
  start(): Promise<unknown>
  stop(): void
  onNotification(handler: (method: string, params: unknown) => void): void
  onServerRequest(handler: ServerRequestHandler): void
  startThread(options: ThreadStartOptions): Promise<{ threadId: string; raw: unknown }>
  resumeThread(threadId: string, options?: Partial<ThreadStartOptions>): Promise<unknown>
  forkThread(threadId: string): Promise<{ threadId: string }>
  startTurn(options: TurnStartOptions): Promise<{ turnId: string | null }>
  steerTurn(threadId: string, expectedTurnId: string, input: Array<Record<string, unknown>>): Promise<void>
  interruptTurn(threadId: string, turnId: string): Promise<void>
  readThread(threadId: string, includeTurns?: boolean): Promise<CodexThread>
  archiveThread(threadId: string): Promise<void>
  unarchiveThread(threadId: string): Promise<void>
  deleteThread(threadId: string): Promise<void>
  listModels(): Promise<unknown>
  listPermissionProfiles(cwd: string): Promise<unknown>
  listSkills(cwd: string): Promise<unknown>
  listPlugins(cwd: string): Promise<unknown>
  listApps(): Promise<unknown>
}

export interface RuntimeRelay {
  poll(limit?: number): Promise<{ payloads: WirePayload[]; gapDetected: boolean }>
  publish(type: string, body: unknown, streamPrefix: string, requestId?: string | null): Promise<number>
  requestState(requestId: string): {
    state: 'inflight' | 'completed'
    updatedAt: number
    result?: unknown
    error?: string
  } | undefined
  markRequest(requestId: string, state: 'inflight' | 'completed', result?: unknown, error?: string): void
}

export class WireCodexRuntimeBridge {
  private readonly runtimes = new Map<string, RuntimeBinding>()
  private readonly approvals = new Map<string, PendingApproval>()
  private readonly interactions = new Map<string, PendingInteraction>()
  private readonly uploads = new Map<string, {
    chunks: Buffer[]
    chunkCount: number
    fileName: string
    mime: string
    sha256: string
    updatedAt: number
  }>()
  private readonly clientFactory: () => RuntimeCodexClient
  private readonly uploadDirectory: string
  private readonly now: () => number

  constructor(
    private readonly relay: RuntimeRelay,
    private readonly options: RuntimeBridgeOptions,
  ) {
    this.clientFactory = options.clientFactory ?? (() => new CodexAppServerClient())
    this.uploadDirectory = options.uploadDirectory ?? defaultUploadRoot()
    this.now = options.now ?? Date.now
  }

  async pollOnce(): Promise<number> {
    const { payloads, gapDetected } = await this.relay.poll(200)
    if (gapDetected) throw new Error('Android 控制请求序列不连续')
    let handled = 0
    for (const payload of payloads) {
      if (payload.type !== 'runtime.command') continue
      await this.handlePayload(payload)
      handled += 1
    }
    return handled
  }

  async shutdown(): Promise<void> {
    for (const runtime of this.runtimes.values()) runtime.client.stop()
    this.runtimes.clear()
    for (const approval of this.approvals.values()) approval.resolve('cancel')
    this.approvals.clear()
    for (const interaction of this.interactions.values()) interaction.resolve({ answers: {} })
    this.interactions.clear()
  }

  private async handlePayload(payload: WirePayload): Promise<void> {
    const requestId = requireString(payload.requestId, 'requestId')
    const command = parseRuntimeCommand(payload.body)
    if (command.machineId !== this.options.machineId) return
    const prior = this.relay.requestState(requestId)
    if (prior?.state === 'completed') {
      await this.publishResult(requestId, command, prior.error === undefined, prior.result, prior.error)
      return
    }
    if (prior?.state === 'inflight') {
      await this.publishResult(requestId, command, false, undefined, '请求上次执行时中断，为避免重复操作未自动重试')
      return
    }
    this.relay.markRequest(requestId, 'inflight')
    try {
      const result = await this.execute(command, requestId)
      this.relay.markRequest(requestId, 'completed', result)
      await this.publishResult(requestId, command, true, result)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      this.relay.markRequest(requestId, 'completed', undefined, message)
      await this.publishResult(requestId, command, false, undefined, message)
    }
  }

  private async execute(command: RuntimeCommand, requestId: string): Promise<unknown> {
    switch (command.command) {
      case 'attachment.upload': {
        return this.acceptAttachmentChunk(command)
      }
      case 'attachment.download': {
        return this.readAttachmentChunk(command)
      }
      case 'runtime.catalog': {
        const cwd = requireString(command.cwd, 'cwd')
        const client = this.clientFactory()
        await this.startClient(client)
        try {
          const [models, permissionProfiles, skills, plugins, apps] = await Promise.all([
            client.listModels(),
            client.listPermissionProfiles(cwd),
            client.listSkills(cwd),
            client.listPlugins(cwd).catch(() => ({ marketplaces: [] })),
            client.listApps().catch(() => ({ data: [] })),
          ])
          const catalog = {
            machineId: this.options.machineId,
            cwd,
            models: responseData(models),
            permissionProfiles: responseData(permissionProfiles),
            skills: responseSkills(skills),
            plugins: responsePlugins(plugins),
            apps: responseData(apps),
            generatedAt: Date.now(),
          }
          await this.relay.publish('runtime.catalog', catalog, `runtime_catalog_${this.options.machineId}`, requestId)
          return { cwd, modelCount: catalog.models.length, skillCount: catalog.skills.length }
        } finally {
          client.stop()
        }
      }
      case 'thread.detail': {
        const threadId = requireString(command.threadId, 'threadId')
        const runtime = this.runtimes.get(threadId)
        const client = runtime?.client ?? this.clientFactory()
        if (!runtime) await this.startClient(client)
        try {
          const detail = normalizeThreadDetail(this.options.machineId, await client.readThread(threadId, true))
          await this.publishThreadDetailPayload(detail, threadId, requestId)
          return { threadId }
        } finally {
          if (!runtime) client.stop()
        }
      }
      case 'thread.start': {
        const client = this.clientFactory()
        await this.startClient(client)
        const started = await client.startThread({
          cwd: requireString(command.cwd, 'cwd'),
          approvalPolicy: command.approvalPolicy ?? 'untrusted',
          sandbox: command.sandbox ?? 'workspace-write',
          model: command.model,
          reasoningEffort: command.effort,
          serviceTier: command.serviceTier,
          permissions: command.permissions,
        })
        const runtime = this.bind(client, started.threadId)
        await this.publishConfirmedSettings(started.threadId, started.raw)
        await this.publishThreadDetail(runtime, false)
        await this.publishEvent(started.threadId, 'runtime.connected', {
          bindingId: runtime.bindingId,
          state: 'idle',
        })
        if (hasInput(command)) await this.startTurn(runtime, command)
        return { threadId: started.threadId, bindingId: runtime.bindingId }
      }
      case 'thread.resume':
      case 'turn.start': {
        const threadId = requireString(command.threadId, 'threadId')
        let runtime = this.runtimes.get(threadId)
        if (!runtime) {
          if (!command.confirmedUnknown) throw new Error('该任务由桌面端创建，继续前需要用户确认接管')
          const client = this.clientFactory()
          await this.startClient(client)
          const resumed = await client.resumeThread(threadId, {
            approvalPolicy: command.approvalPolicy ?? 'untrusted',
            sandbox: command.sandbox ?? 'workspace-write',
            model: command.model,
            reasoningEffort: command.effort,
            serviceTier: command.serviceTier,
            permissions: command.permissions,
          })
          runtime = this.bind(client, threadId)
          await this.publishConfirmedSettings(threadId, resumed)
        }
        if (hasInput(command)) await this.startTurn(runtime, command)
        return { threadId, bindingId: runtime.bindingId }
      }
      case 'thread.fork': {
        const sourceId = requireString(command.threadId, 'threadId')
        const client = this.clientFactory()
        await this.startClient(client)
        const forked = await client.forkThread(sourceId)
        const runtime = this.bind(client, forked.threadId)
        await this.publishThreadDetail(runtime, false)
        await this.publishEvent(forked.threadId, 'runtime.connected', {
          bindingId: runtime.bindingId,
          state: 'idle',
        })
        return { threadId: forked.threadId, bindingId: runtime.bindingId, forkedFromId: sourceId }
      }
      case 'turn.steer': {
        const runtime = this.requireRuntime(command.threadId)
        await runtime.client.steerTurn(
          runtime.threadId,
          requireString(runtime.activeTurnId, 'activeTurnId'),
          runtimeInput(command),
        )
        return { threadId: runtime.threadId }
      }
      case 'turn.interrupt': {
        const runtime = this.requireRuntime(command.threadId)
        await runtime.client.interruptTurn(runtime.threadId, requireString(runtime.activeTurnId, 'activeTurnId'))
        return { threadId: runtime.threadId }
      }
      case 'approval.resolve': {
        const approvalId = requireString(command.approvalId, 'approvalId')
        const approval = this.approvals.get(approvalId)
        if (!approval) throw new Error('审批已失效或已处理')
        approval.resolve(command.decision ?? 'decline')
        this.approvals.delete(approvalId)
        await this.publishEvent(approval.threadId, 'approval.resolved', { approvalId, decision: command.decision ?? 'decline' })
        return { threadId: approval.threadId, approvalId }
      }
      case 'interaction.resolve': {
        const approvalId = requireString(command.approvalId, 'approvalId')
        const interaction = this.interactions.get(approvalId)
        if (!interaction) throw new Error('交互请求已失效')
        const response = normalizeUserInputAnswer(requireString(command.answer, 'answer'))
        interaction.resolve(response)
        this.interactions.delete(approvalId)
        await this.publishEvent(interaction.threadId, 'approval.resolved', { approvalId, decision: 'answered' })
        return { threadId: interaction.threadId, approvalId }
      }
      case 'thread.archive':
      case 'thread.unarchive':
      case 'thread.delete': {
        const threadId = requireString(command.threadId, 'threadId')
        const runtime = this.runtimes.get(threadId)
        const client = runtime?.client ?? this.clientFactory()
        if (!runtime) await this.startClient(client)
        try {
          if (command.command === 'thread.archive') await client.archiveThread(threadId)
          if (command.command === 'thread.unarchive') await client.unarchiveThread(threadId)
          if (command.command === 'thread.delete') await client.deleteThread(threadId)
          await this.publishEvent(threadId, command.command.replace('thread.', 'thread.'), {})
          if (command.command === 'thread.delete') this.removeRuntime(threadId)
          return { threadId }
        } finally {
          if (!runtime) client.stop()
        }
      }
    }
  }

  private bind(client: RuntimeCodexClient, threadId: string): RuntimeBinding {
    const existing = this.runtimes.get(threadId)
    if (existing) {
      client.stop()
      return existing
    }
    const runtime: RuntimeBinding = {
      bindingId: randomUUID(),
      threadId,
      client,
      activeTurnId: null,
      deltas: new Map(),
      items: new Map(),
    }
    client.onNotification((method, params) => {
      void this.handleNotification(runtime, method, (params ?? {}) as Record<string, unknown>).catch((error) => {
        log(`Wire runtime 事件处理失败 ${method}: ${error instanceof Error ? error.message : String(error)}`)
      })
    })
    client.onServerRequest(this.approvalHandler(runtime))
    this.runtimes.set(threadId, runtime)
    void this.publishEvent(threadId, 'runtime.connected', { bindingId: runtime.bindingId, state: 'idle' })
      .catch((error) => log(`Wire runtime 连接事件发布失败: ${error instanceof Error ? error.message : String(error)}`))
    return runtime
  }

  private async acceptAttachmentChunk(command: RuntimeCommand): Promise<unknown> {
    await this.pruneExpiredUploads()
    const attachmentId = safeAttachmentId(requireString(command.attachmentId, 'attachmentId'))
    const fileName = safeFileName(requireString(command.fileName, 'fileName'))
    const mime = safeMime(command.mime)
    const sha256 = requireString(command.sha256, 'sha256').toLowerCase()
    const chunkIndex = requireIndex(command.chunkIndex, 'chunkIndex')
    const chunkCount = requireIndex(command.chunkCount, 'chunkCount')
    if (chunkCount < 1 || chunkCount > 64 || chunkIndex >= chunkCount) throw new Error('附件分片范围无效')
    const chunk = Buffer.from(requireString(command.contentBase64, 'contentBase64'), 'base64')
    if (chunk.length > 512 * 1024) throw new Error('附件分片超过 512 KiB')
    const current = this.uploads.get(attachmentId) ?? {
      chunks: Array.from({ length: chunkCount }, () => Buffer.alloc(0)),
      chunkCount,
      fileName,
      mime,
      sha256,
      updatedAt: this.now(),
    }
    if (current.chunkCount !== chunkCount || current.fileName !== fileName || current.mime !== mime || current.sha256 !== sha256) {
      throw new Error('附件分片元数据冲突')
    }
    current.chunks[chunkIndex] = chunk
    current.updatedAt = this.now()
    this.uploads.set(attachmentId, current)
    const received = current.chunks.filter((value) => value.length > 0).length
    if (received < chunkCount) return { attachmentId, received, chunkCount, complete: false }

    const content = Buffer.concat(current.chunks)
    if (content.length > 20 * 1024 * 1024) throw new Error('附件超过 20 MiB')
    if (createHash('sha256').update(content).digest('hex') !== sha256) throw new Error('附件哈希校验失败')
    const directory = join(this.uploadDirectory, attachmentId)
    await mkdir(directory, { recursive: true })
    const localPath = join(directory, fileName)
    await writeFile(localPath, content, { flag: 'wx' }).catch(async (error: NodeJS.ErrnoException) => {
      if (error.code !== 'EEXIST') throw error
      await writeFile(localPath, content)
    })
    this.uploads.delete(attachmentId)
    return { attachmentId, received, chunkCount, complete: true, localPath, mime }
  }

  private async pruneExpiredUploads(now = this.now()): Promise<void> {
    for (const [id, upload] of this.uploads) {
      if (now - upload.updatedAt > UPLOAD_TTL_MS) this.uploads.delete(id)
    }
    const root = this.uploadDirectory
    const entries = await readdir(root, { withFileTypes: true }).catch(() => [])
    await Promise.all(entries.filter((entry) => entry.isDirectory()).map(async (entry) => {
      const directory = join(root, entry.name)
      const info = await stat(directory).catch(() => null)
      if (info && now - info.mtimeMs > UPLOAD_TTL_MS) await rm(directory, { recursive: true, force: true })
    }))
  }

  private async readAttachmentChunk(command: RuntimeCommand): Promise<unknown> {
    const threadId = requireString(command.threadId, 'threadId')
    const requestedPath = resolve(requireString(command.path, 'path'))
    const chunkIndex = requireIndex(command.chunkIndex ?? 0, 'chunkIndex')
    const runtime = this.runtimes.get(threadId)
    const client = runtime?.client ?? this.clientFactory()
    if (!runtime) await this.startClient(client)
    try {
      const thread = await client.readThread(threadId, true)
      const allowedPaths = collectLocalImagePaths(thread.raw)
      if (!allowedPaths.has(requestedPath)) throw new Error('附件路径不属于该 Codex 任务')
      const info = await stat(requestedPath)
      if (!info.isFile()) throw new Error('附件不是普通文件')
      if (info.size > 20 * 1024 * 1024) throw new Error('附件超过 20 MiB')
      const content = await readFile(requestedPath)
      const chunkCount = Math.max(1, Math.ceil(content.length / DOWNLOAD_CHUNK_BYTES))
      if (chunkIndex >= chunkCount) throw new Error('附件下载分片范围无效')
      const chunk = content.subarray(
        chunkIndex * DOWNLOAD_CHUNK_BYTES,
        Math.min(content.length, (chunkIndex + 1) * DOWNLOAD_CHUNK_BYTES),
      )
      return {
        path: requestedPath,
        fileName: safeFileName(basename(requestedPath)),
        mime: imageMime(requestedPath),
        chunkIndex,
        chunkCount,
        contentBase64: chunk.toString('base64'),
        sha256: createHash('sha256').update(content).digest('hex'),
      }
    } finally {
      if (!runtime) client.stop()
    }
  }

  private async startClient(client: RuntimeCodexClient): Promise<void> {
    await client.start()
    if (client instanceof CodexAppServerClient) {
      const info = client.serverInfo
      if (!info) throw new Error('codex app-server 初始化信息缺失')
      const compatibility = analyzeCodexCompatibility(
        info,
        await generateCodexSchemaHash(client.binaryPath),
      )
      if (!compatibility.runtimeWritable) {
        client.stop()
        throw new Error(compatibility.reason ?? '当前 Codex 版本仅支持读取历史')
      }
    }
  }

  private async startTurn(runtime: RuntimeBinding, command: RuntimeCommand): Promise<void> {
    if (runtime.activeTurnId) throw new Error('任务正在运行，请使用“补充要求”或先停止')
    const turn = await runtime.client.startTurn({
      threadId: runtime.threadId,
      input: runtimeInput(command),
      model: command.model,
      effort: command.effort,
      serviceTier: command.serviceTier,
      permissions: command.permissions,
    })
    runtime.activeTurnId = turn.turnId
  }

  private async handleNotification(
    runtime: RuntimeBinding,
    method: string,
    params: Record<string, unknown>,
  ): Promise<void> {
    if (method === 'turn/started') {
      const turn = params.turn as Record<string, unknown> | undefined
      runtime.activeTurnId = String(turn?.id ?? runtime.activeTurnId ?? '') || null
      await this.publishEvent(runtime.threadId, 'turn.started', { turnId: runtime.activeTurnId })
      return
    }
    if (method === 'item/agentMessage/delta') {
      const itemId = String(params.itemId ?? '')
      const text = (runtime.deltas.get(itemId) ?? '') + String(params.delta ?? '')
      runtime.deltas.set(itemId, text)
      const item = { ...(runtime.items.get(itemId) ?? {}), id: itemId, type: 'agentMessage', text }
      runtime.items.set(itemId, item)
      await this.publishEvent(runtime.threadId, 'item.delta', {
        turnId: runtime.activeTurnId,
        itemId,
        itemType: 'agentMessage',
        role: 'agent',
        text,
        payload: item,
      })
      return
    }
    if (method === 'thread/settings/updated') {
      const settings = (params.threadSettings ?? {}) as Record<string, unknown>
      const activeProfile = (settings.activePermissionProfile ?? {}) as Record<string, unknown>
      await this.publishEvent(runtime.threadId, 'thread.settings', {
        model: settings.model ?? null,
        effort: settings.effort ?? null,
        serviceTier: settings.serviceTier ?? null,
        permissions: activeProfile.id ?? null,
        payload: settings,
      })
      return
    }
    if (method === 'thread/tokenUsage/updated') {
      const usage = (params.tokenUsage ?? {}) as Record<string, unknown>
      const total = (usage.total ?? {}) as Record<string, unknown>
      await this.publishEvent(runtime.threadId, 'token.usage', {
        turnId: params.turnId ?? runtime.activeTurnId,
        usedTokens: total.totalTokens ?? null,
        contextWindow: usage.modelContextWindow ?? null,
        payload: usage,
      })
      return
    }
    if (method.includes('reasoning') && method.toLowerCase().endsWith('delta')) {
      const itemId = String(params.itemId ?? '')
      const text = (runtime.deltas.get(itemId) ?? '') + String(params.delta ?? '')
      runtime.deltas.set(itemId, text)
      const item = { ...(runtime.items.get(itemId) ?? {}), id: itemId, type: 'reasoning', summary: [text] }
      runtime.items.set(itemId, item)
      await this.publishEvent(runtime.threadId, 'item.delta', {
        turnId: runtime.activeTurnId,
        itemId,
        itemType: 'reasoning',
        role: 'agent',
        text,
        status: 'inProgress',
        payload: item,
      })
      return
    }
    if (method.includes('commandExecution') && method.toLowerCase().endsWith('delta')) {
      const itemId = String(params.itemId ?? '')
      const output = (runtime.deltas.get(itemId) ?? '') + String(params.delta ?? '')
      runtime.deltas.set(itemId, output)
      const item = {
        ...(runtime.items.get(itemId) ?? {}),
        id: itemId,
        type: 'commandExecution',
        aggregatedOutput: output,
      }
      runtime.items.set(itemId, item)
      await this.publishEvent(runtime.threadId, 'item.delta', {
        turnId: runtime.activeTurnId,
        itemId,
        itemType: 'commandExecution',
        role: 'tool',
        text: summarizeItem(item),
        status: 'inProgress',
        payload: item,
      })
      return
    }
    if (method === 'item/started' || method === 'item/completed') {
      const item = (params.item ?? {}) as Record<string, unknown>
      const itemId = String(item.id ?? '')
      const type = String(item.type ?? 'unknown')
      const text = type === 'agentMessage'
        ? String(item.text ?? runtime.deltas.get(itemId) ?? '')
        : summarizeItem(item)
      runtime.items.set(itemId, item)
      await this.publishEvent(runtime.threadId, method === 'item/started' ? 'item.started' : 'item.completed', {
        turnId: runtime.activeTurnId,
        itemId,
        itemType: type,
        role: type === 'agentMessage' ? 'agent' : type === 'userMessage' ? 'user' : 'tool',
        text,
        status: item.status ?? null,
        payload: item,
      })
      if (method === 'item/completed') {
        runtime.deltas.delete(itemId)
        runtime.items.delete(itemId)
      }
      return
    }
    if (method === 'turn/completed') {
      const turn = (params.turn ?? {}) as Record<string, unknown>
      const turnId = String(turn.id ?? runtime.activeTurnId ?? '')
      runtime.activeTurnId = null
      await this.publishEvent(runtime.threadId, 'turn.completed', { turnId, status: turn.status ?? 'completed' })
      await this.publishThreadDetail(runtime)
      return
    }
    if (method === 'error') {
      await this.publishEvent(runtime.threadId, 'error', {
        message: String((params as { message?: unknown }).message ?? 'Codex 执行失败'),
      })
    }
  }

  private approvalHandler(runtime: RuntimeBinding): ServerRequestHandler {
    return async (method, rawParams) => {
      const params = (rawParams ?? {}) as Record<string, unknown>
      if (method === 'item/tool/requestUserInput') {
        const itemId = requireString(params.itemId, 'itemId')
        const approvalId = `input_${itemId}`
        const questions = normalizeUserInputQuestions(params.questions)
        const item = { id: itemId, type: 'dynamicToolCall', tool: 'ask_user', arguments: { questions } }
        runtime.items.set(itemId, item)
        await this.publishEvent(runtime.threadId, 'item.started', {
          turnId: params.turnId ?? runtime.activeTurnId,
          itemId,
          itemType: 'dynamicToolCall',
          role: 'tool',
          text: questions.map((question) => question.question).join('\n'),
          status: 'inProgress',
          payload: item,
        })
        const response = new Promise<unknown>((resolve) => {
          this.interactions.set(approvalId, { resolve, threadId: runtime.threadId })
        })
        await this.publishEvent(runtime.threadId, 'approval.requested', {
          approvalId,
          kind: 'user_input',
          summary: questions.map((question) => question.question).join('\n').slice(0, 4_000),
          payload: { itemId, questions },
        })
        return response
      }
      if (method === 'mcpServer/elicitation/request') {
        const approvalId = `mcp_${randomUUID()}`
        const decision = new Promise<string>((resolve) => {
          this.approvals.set(approvalId, { resolve, threadId: runtime.threadId })
        })
        const message = String(params.message ?? 'MCP 工具请求补充信息')
        const url = typeof params.url === 'string' ? `\n${params.url}` : ''
        await this.publishEvent(runtime.threadId, 'approval.requested', {
          approvalId,
          kind: 'mcp_elicitation',
          summary: `${message}${url}`.slice(0, 4_000),
          payload: {
            mode: params.mode ?? null,
            serverName: params.serverName ?? null,
            message: message.slice(0, 4_000),
            url: typeof params.url === 'string' ? params.url.slice(0, 4_000) : null,
          },
        })
        return { action: await decision }
      }
      if (!method.endsWith('/requestApproval')) throw new Error(`未支持的服务端请求: ${method}`)
      const approvalId = String(params.approvalId ?? params.itemId ?? randomUUID())
      const decision = await new Promise<string>((resolve) => {
        this.approvals.set(approvalId, { resolve, threadId: runtime.threadId })
        void this.publishEvent(runtime.threadId, 'approval.requested', {
          approvalId,
          kind: method.includes('commandExecution') ? 'command' : method.includes('fileChange') ? 'file' : 'permissions',
          summary: approvalSummary(method, params),
          payload: sanitizeApproval(params),
        }).catch((error) => {
          log(`Wire runtime 审批事件发布失败: ${error instanceof Error ? error.message : String(error)}`)
          this.approvals.delete(approvalId)
          resolve('decline')
        })
      })
      if (method.includes('permissions/')) return { permissions: decision === 'accept' ? {} : null }
      return { decision }
    }
  }

  private requireRuntime(threadId: string | undefined): RuntimeBinding {
    const id = requireString(threadId, 'threadId')
    const runtime = this.runtimes.get(id)
    if (!runtime) throw new Error('任务尚未由手机接管')
    return runtime
  }

  private removeRuntime(threadId: string): void {
    this.runtimes.get(threadId)?.client.stop()
    this.runtimes.delete(threadId)
  }

  private async publishThreadDetail(runtime: RuntimeBinding, includeTurns = true): Promise<void> {
    const detail = normalizeThreadDetail(
      this.options.machineId,
      await runtime.client.readThread(runtime.threadId, includeTurns),
    )
    await this.publishThreadDetailPayload(detail, runtime.threadId)
  }

  private async publishThreadDetailPayload(detail: unknown, threadId: string, requestId?: string): Promise<void> {
    const content = Buffer.from(JSON.stringify(detail), 'utf8')
    if (content.length <= 384 * 1024) {
      await this.relay.publish('thread.detail', detail, `detail_${this.options.machineId}_${threadId}`, requestId)
      return
    }
    const detailId = randomUUID()
    const contentHash = createHash('sha256').update(content).digest('hex')
    const chunks = Array.from({ length: Math.ceil(content.length / (320 * 1024)) }, (_, index) =>
      content.subarray(index * 320 * 1024, Math.min(content.length, (index + 1) * 320 * 1024)))
    for (const [chunkIndex, chunk] of chunks.entries()) {
      await this.relay.publish('thread.detail.chunk', {
        detailId,
        machineId: this.options.machineId,
        threadId,
        chunkIndex,
        chunkCount: chunks.length,
        contentHash,
        chunkHash: createHash('sha256').update(chunk).digest('hex'),
        contentBase64: chunk.toString('base64url'),
      }, `detail_${this.options.machineId}_${threadId}`, requestId)
    }
  }

  private async publishConfirmedSettings(threadId: string, value: unknown): Promise<void> {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return
    const response = value as Record<string, unknown>
    const activePermissionProfile = response.activePermissionProfile
    const permissions = activePermissionProfile
      && typeof activePermissionProfile === 'object'
      && !Array.isArray(activePermissionProfile)
      ? (activePermissionProfile as Record<string, unknown>).id ?? null
      : null
    const model = response.model ?? null
    const effort = response.reasoningEffort ?? null
    const serviceTier = response.serviceTier ?? null

    await this.publishEvent(threadId, 'thread.settings', {
      model,
      effort,
      serviceTier,
      permissions,
      payload: {
        model,
        reasoningEffort: effort,
        serviceTier,
        activePermissionProfile: permissions == null ? null : { id: permissions },
      },
    })
  }

  private async publishEvent(threadId: string, type: string, data: Record<string, unknown>): Promise<void> {
    await this.relay.publish('runtime.event', {
      machineId: this.options.machineId,
      threadId,
      eventId: randomUUID(),
      type,
      at: Date.now(),
      ...data,
    }, `runtime_${this.options.machineId}_${threadId}`)
  }

  private async publishResult(
    requestId: string,
    command: RuntimeCommand,
    ok: boolean,
    result?: unknown,
    error?: string,
  ): Promise<void> {
    await this.relay.publish('command.result', {
      machineId: this.options.machineId,
      threadId: command.threadId ?? null,
      command: command.command,
      ok,
      result: result ?? null,
      error: error ?? null,
    }, `result_${this.options.machineId}`, requestId)
  }
}

function parseRuntimeCommand(value: unknown): RuntimeCommand {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('runtime.command body 格式错误')
  const body = value as Record<string, unknown>
  return {
    ...body,
    command: requireString(body.command, 'command') as RuntimeCommandName,
    machineId: requireString(body.machineId, 'machineId'),
  } as RuntimeCommand
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`${field} 不能为空`)
  return value
}

function summarizeItem(item: Record<string, unknown>): string {
  if (item.type === 'userMessage') return userMessageText(item.content)
  if (item.type === 'commandExecution') return String(item.command ?? '执行命令')
  if (item.type === 'fileChange') return '修改文件'
  if (item.type === 'reasoning') {
    return Array.isArray(item.summary) ? item.summary.join('\n') : String(item.summary ?? '思考')
  }
  return String(item.text ?? item.type ?? '活动')
}

function userMessageText(value: unknown): string {
  if (!Array.isArray(value)) return ''
  return value
    .map((part) => {
      if (!part || typeof part !== 'object' || Array.isArray(part)) return ''
      const record = part as Record<string, unknown>
      return record.type === 'text' ? String(record.text ?? '') : ''
    })
    .filter(Boolean)
    .join('\n')
}

function approvalSummary(method: string, params: Record<string, unknown>): string {
  if (method.includes('commandExecution')) return String(params.command ?? params.reason ?? '执行命令')
  if (method.includes('fileChange')) return String(params.grantRoot ?? params.reason ?? '修改文件')
  return String(params.reason ?? '请求额外权限')
}

function sanitizeApproval(params: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(params)
      .filter(([key]) => ['command', 'cwd', 'reason', 'grantRoot', 'itemId', 'turnId'].includes(key))
      .map(([key, value]) => [key, typeof value === 'string' ? value.slice(0, 4_000) : value]),
  )
}

function normalizeUserInputQuestions(value: unknown): Array<{
  id: string
  question: string
  options: string[]
  selection_type: 'single' | 'text'
}> {
  if (!Array.isArray(value) || value.length === 0 || value.length > 3) throw new Error('交互问题数量无效')
  return value.map((entry) => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) throw new Error('交互问题格式无效')
    const question = entry as Record<string, unknown>
    const options = Array.isArray(question.options)
      ? question.options.map((option) => {
        if (!option || typeof option !== 'object' || Array.isArray(option)) throw new Error('交互选项格式无效')
        return requireString((option as Record<string, unknown>).label, 'option.label').slice(0, 200)
      }).slice(0, 3)
      : []
    return {
      id: requireString(question.id, 'question.id').slice(0, 100),
      question: requireString(question.question, 'question.question').slice(0, 2_000),
      options,
      selection_type: options.length > 0 && question.isOther !== true ? 'single' : 'text',
    }
  })
}

function normalizeUserInputAnswer(value: string): { answers: Record<string, { answers: string[] }> } {
  const parsed = JSON.parse(value) as { answers?: unknown }
  if (!parsed.answers || typeof parsed.answers !== 'object' || Array.isArray(parsed.answers)) {
    throw new Error('交互回答格式无效')
  }
  return {
    answers: Object.fromEntries(Object.entries(parsed.answers as Record<string, unknown>).map(([id, answer]) => {
      const values = Array.isArray(answer)
        ? answer
        : answer && typeof answer === 'object' && !Array.isArray(answer) && Array.isArray((answer as { answers?: unknown }).answers)
          ? (answer as { answers: unknown[] }).answers
          : [answer]
      return [id, { answers: values.map((entry) => String(entry ?? '')).filter(Boolean).slice(0, 10) }]
    })),
  }
}

function requireIndex(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error(`${field} 必须是非负整数`)
  return value
}

export function safeFileName(value: string): string {
  const result = basename(value).replace(/[^\p{L}\p{N}._-]/gu, '_').slice(0, 160)
  if (!result || result === '.' || result === '..') throw new Error('附件文件名无效')
  return result
}

export function safeAttachmentId(value: string): string {
  if (!/^[A-Za-z0-9_-]{8,128}$/u.test(value)) throw new Error('附件 ID 无效')
  return value
}

export function safeMime(value: unknown): string {
  if (value === undefined || value === null || value === '') return 'application/octet-stream'
  if (typeof value !== 'string' || value.length > 127 || !/^[\w.+-]+\/[\w.+-]+$/u.test(value)) {
    throw new Error('附件 MIME 无效')
  }
  return value
}

function defaultUploadRoot(): string {
  return join(homedir(), '.zhixing-agent', 'uploads')
}

const UPLOAD_TTL_MS = 24 * 60 * 60 * 1_000
const DOWNLOAD_CHUNK_BYTES = 320 * 1024

function collectLocalImagePaths(value: unknown, output = new Set<string>(), depth = 0): Set<string> {
  if (depth > 12 || value === null || value === undefined) return output
  if (Array.isArray(value)) {
    value.forEach((entry) => collectLocalImagePaths(entry, output, depth + 1))
    return output
  }
  if (typeof value !== 'object') return output
  const record = value as Record<string, unknown>
  if (record.type === 'localImage' && typeof record.path === 'string') output.add(resolve(record.path))
  Object.values(record).forEach((entry) => collectLocalImagePaths(entry, output, depth + 1))
  return output
}

function imageMime(path: string): string {
  switch (extname(path).toLowerCase()) {
    case '.png': return 'image/png'
    case '.jpg':
    case '.jpeg': return 'image/jpeg'
    case '.webp': return 'image/webp'
    case '.gif': return 'image/gif'
    default: return 'application/octet-stream'
  }
}

function runtimeInput(command: RuntimeCommand): Array<Record<string, unknown>> {
  if (command.input?.length) return command.input
  if (command.text?.trim()) return [{ type: 'text', text: command.text }]
  throw new Error('input 不能为空')
}

function hasInput(command: RuntimeCommand): boolean {
  return Boolean(command.input?.length || command.text?.trim())
}

function responseData(value: unknown): unknown[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return []
  const data = (value as Record<string, unknown>).data
  return Array.isArray(data) ? data : []
}

function responseSkills(value: unknown): unknown[] {
  return responseData(value).flatMap((entry) => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return []
    const skills = (entry as Record<string, unknown>).skills
    return Array.isArray(skills) ? skills : []
  })
}

function responsePlugins(value: unknown): unknown[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return []
  const marketplaces = (value as Record<string, unknown>).marketplaces
  if (!Array.isArray(marketplaces)) return []
  return marketplaces.flatMap((entry) => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return []
    const plugins = (entry as Record<string, unknown>).plugins
    return Array.isArray(plugins) ? plugins : []
  })
}
