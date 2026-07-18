import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import type {
  CodexInitializeInfo,
  CodexThread,
  CodexThreadListOptions,
  CodexThreadListPage,
} from '../codex/protocol.js'
import { CodexCatalogService, type CodexCatalogClient } from './codexCatalog.js'
import { CODEX_SCHEMA_BASELINE } from '../codex/compatibility.js'
import { normalizeProjectPath } from './pathNormalization.js'
import { ProjectRegistry } from './projectRegistry.js'

const tempDirectories: string[] = []

afterEach(async () => {
  await Promise.all(tempDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })))
})

describe('project path and registry', () => {
  it('normalizes Windows, extended, WSL mount, and POSIX roots', () => {
    expect(normalizeProjectPath('c:/Users/Test/Repo/', 'windows')).toEqual({
      canonicalRoot: 'C:\\Users\\Test\\Repo',
      lookupKey: 'c:\\users\\test\\repo',
      displayName: 'Repo',
    })
    expect(normalizeProjectPath('\\\\?\\C:\\Users\\Test\\Repo\\', 'windows').canonicalRoot).toBe(
      'C:\\Users\\Test\\Repo',
    )
    expect(normalizeProjectPath('/mnt/c/Users/Test/Repo', 'windows').canonicalRoot).toBe(
      'C:\\Users\\Test\\Repo',
    )
    expect(normalizeProjectPath('/home/test/repo/', 'unix').canonicalRoot).toBe('/home/test/repo')
  })

  it('persists a random project id without using a naked path hash', async () => {
    const { registry, stateFile } = await registryFixture()
    const root = normalizeProjectPath('C:\\Users\\Test\\Repo', 'windows')
    const first = await registry.resolve('machine_1', root)
    const second = await new ProjectRegistry(stateFile).resolve('machine_1', root)

    expect(second.projectId).toBe(first.projectId)
    expect(first.projectId).toMatch(/^[0-9a-f-]{36}$/)
    expect(await readFile(stateFile, 'utf8')).toContain(first.projectId)
  })
})

describe('Codex catalog', () => {
  it('fully paginates active and archived history without an activity window', async () => {
    const { registry } = await registryFixture()
    const client = new FakeCatalogClient({
      'active:root': page([thread('thread_new'), thread('sub_1', { parentThreadId: 'thread_new', source: { subAgent: {} } })], 'active:2'),
      'active:active:2': page([thread('thread_old', { updatedAt: 1_500_000_000, recencyAt: 1_500_000_000 })]),
      'archived:root': page([thread('thread_archived')]),
    })
    const service = serviceFor(client, registry)

    const snapshot = await service.snapshot()

    expect(snapshot.threads.map((item) => item.threadId).sort()).toEqual([
      'sub_1',
      'thread_archived',
      'thread_new',
      'thread_old',
    ])
    expect(snapshot.threads.find((item) => item.threadId === 'thread_old')?.runtimeState).toBe('unknown')
    expect(snapshot.threads.find((item) => item.threadId === 'sub_1')?.isSubagent).toBe(true)
    expect(snapshot.threads.find((item) => item.threadId === 'thread_archived')?.archived).toBe(true)
    expect(snapshot.threads.find((item) => item.threadId === 'thread_new')?.isAutomation).toBe(false)
    expect(client.calls).toHaveLength(3)
  })

  it('increments revision only when catalog content changes', async () => {
    const { registry } = await registryFixture()
    const client = new FakeCatalogClient({
      'active:root': page([thread('thread_1')]),
      'archived:root': page([]),
    })
    const service = serviceFor(client, registry)
    const first = await service.snapshot()
    const second = await service.snapshot()
    client.pages['active:root'] = page([thread('thread_1', { status: { type: 'active', activeFlags: [] } })])
    const third = await service.snapshot()

    expect(first.revision).toBe(1)
    expect(second.revision).toBe(1)
    expect(third.revision).toBe(2)
    expect(third.threads[0].runtimeState).toBe('running')
  })

  it('normalizes on-demand turns and isolates unknown item kinds', async () => {
    const { registry } = await registryFixture()
    const client = new FakeCatalogClient({ 'active:root': page([]), 'archived:root': page([]) })

    const detail = await serviceFor(client, registry).threadDetail('thread_1')

    expect(detail.turns[0]).toMatchObject({
      turnId: 'turn_1',
      status: 'completed',
      startedAt: 1_700_000_000_000,
      items: [
        { type: 'userMessage', role: 'user', text: '继续' },
        { type: 'agentMessage', role: 'agent', text: '已完成' },
        { type: 'opaque', rawType: 'futureItem', role: 'unknown' },
      ],
    })
    expect(detail.turns[0].items[2].raw).toEqual({ type: 'futureItem', id: 'future_1', payload: { kept: true } })
  })

  it('rejects cursor loops instead of silently truncating history', async () => {
    const { registry } = await registryFixture()
    const client = new FakeCatalogClient({
      'active:root': page([], 'same'),
      'active:same': page([], 'same'),
    })
    await expect(serviceFor(client, registry).snapshot()).rejects.toThrow(/repeated cursor/)
  })
})

class FakeCatalogClient implements CodexCatalogClient {
  readonly calls: CodexThreadListOptions[] = []
  readonly serverInfo: CodexInitializeInfo = {
    userAgent: 'Codex Desktop/0.144.0 (Windows; x86_64)',
    codexHome: 'C:\\Users\\test\\.codex',
    platformFamily: 'windows',
    platformOs: 'windows',
  }

  constructor(readonly pages: Record<string, CodexThreadListPage>) {}

  async listThreadsPage(options: CodexThreadListOptions): Promise<CodexThreadListPage> {
    this.calls.push(options)
    const key = `${options.archived ? 'archived' : 'active'}:${options.cursor ?? 'root'}`
    return this.pages[key] ?? page([])
  }

  async readThread(threadId: string): Promise<CodexThread> {
    return thread(threadId, {
      turns: [
        {
          id: 'turn_1',
          status: 'completed',
          startedAt: 1_700_000_000,
          completedAt: 1_700_000_001,
          durationMs: 1_000,
          error: null,
          items: [
            { type: 'userMessage', id: 'user_1', content: [{ type: 'text', text: '继续', text_elements: [] }] },
            { type: 'agentMessage', id: 'agent_1', text: '已完成', phase: null },
            { type: 'futureItem', id: 'future_1', payload: { kept: true } },
          ],
        },
      ],
    })
  }
}

function serviceFor(client: FakeCatalogClient, registry: ProjectRegistry): CodexCatalogService {
  return new CodexCatalogService(client, registry, {
    machineId: 'machine_1',
    machineDisplayName: 'Minecraft',
    agentVersion: '0.2.0',
    schemaHash: CODEX_SCHEMA_BASELINE.schemaHash,
    now: () => 1_800_000_000_000,
    gitRootDetector: async (cwd) => cwd,
  })
}

async function registryFixture(): Promise<{ registry: ProjectRegistry; stateFile: string }> {
  const directory = await mkdtemp(path.join(tmpdir(), 'zhixing-catalog-'))
  tempDirectories.push(directory)
  const stateFile = path.join(directory, 'projects.json')
  return { registry: new ProjectRegistry(stateFile), stateFile }
}

function page(data: CodexThread[], nextCursor: string | null = null): CodexThreadListPage {
  return { data, nextCursor, backwardsCursor: null }
}

function thread(id: string, overrides: Partial<CodexThread> = {}): CodexThread {
  return {
    id,
    sessionId: `session_${id}`,
    forkedFromId: null,
    parentThreadId: null,
    preview: `preview ${id}`,
    ephemeral: false,
    modelProvider: 'openai',
    createdAt: 1_700_000_000,
    updatedAt: 1_700_000_100,
    recencyAt: 1_700_000_100,
    status: { type: 'notLoaded' },
    cwd: 'C:\\Users\\test\\zhixing',
    cliVersion: '0.144.0',
    source: 'vscode',
    threadSource: null,
    agentNickname: null,
    agentRole: null,
    gitInfo: { sha: null, branch: 'main', originUrl: 'https://github.com/rgywd/zhixing.git' },
    name: id,
    turns: [],
    raw: {},
    ...overrides,
  }
}
