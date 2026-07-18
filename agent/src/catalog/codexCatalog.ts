import { createHash } from 'node:crypto'
import { hostname } from 'node:os'
import type { CodexCompatibility } from '../codex/compatibility.js'
import { analyzeCodexCompatibility } from '../codex/compatibility.js'
import type {
  CodexInitializeInfo,
  CodexThread,
  CodexThreadListOptions,
  CodexThreadListPage,
} from '../codex/protocol.js'
import { detectGitRoot, normalizeProjectPath } from './pathNormalization.js'
import { ProjectRegistry } from './projectRegistry.js'
import type {
  CatalogProject,
  CatalogRuntimeState,
  CatalogThreadSummary,
  CodexCatalogSnapshot,
} from './types.js'
import { normalizeThreadDetail } from './threadDetail.js'

const SOURCE_KINDS = [
  'cli',
  'vscode',
  'exec',
  'appServer',
  'subAgent',
  'subAgentReview',
  'subAgentCompact',
  'subAgentThreadSpawn',
  'subAgentOther',
  'unknown',
]
const AUTOMATION_THREAD_SOURCES = new Set(['automation', 'scheduled'])
const PAGE_LIMIT = 100
const MAX_PAGES = 1_000

export interface CodexCatalogClient {
  serverInfo: CodexInitializeInfo | null
  listThreadsPage(options: CodexThreadListOptions): Promise<CodexThreadListPage>
  readThread(threadId: string, includeTurns?: boolean): Promise<CodexThread>
}

export interface CodexCatalogOptions {
  machineId: string
  machineDisplayName?: string
  agentVersion: string
  schemaHash: string | null
  now?: () => number
  gitRootDetector?: (cwd: string) => Promise<string | null>
}

export class CodexCatalogService {
  private readonly now: () => number
  private readonly gitRootDetector: (cwd: string) => Promise<string | null>

  constructor(
    private readonly client: CodexCatalogClient,
    private readonly registry: ProjectRegistry,
    private readonly options: CodexCatalogOptions,
  ) {
    this.now = options.now ?? Date.now
    this.gitRootDetector = options.gitRootDetector ?? detectGitRoot
  }

  async snapshot(): Promise<CodexCatalogSnapshot> {
    const info = this.client.serverInfo
    if (!info) throw new Error('codex app-server is not initialized')
    const compatibility = analyzeCodexCompatibility(info, this.options.schemaHash)
    const active = await this.listAll(false)
    const archived = await this.listAll(true)
    const byId = new Map<string, { thread: CodexThread; archived: boolean }>()
    for (const thread of active) byId.set(thread.id, { thread, archived: false })
    for (const thread of archived) if (!byId.has(thread.id)) byId.set(thread.id, { thread, archived: true })

    const projectById = new Map<string, CatalogProject>()
    const threads: CatalogThreadSummary[] = []
    const entries = [...byId.values()].filter(({ thread }) => !thread.ephemeral)
    const roots = await detectRoots(
      [...new Set(entries.map(({ thread }) => thread.cwd))],
      this.gitRootDetector,
      8,
    )
    for (const { thread, archived: isArchived } of entries) {
      const gitRoot = roots.get(thread.cwd) ?? null
      const normalized = normalizeProjectPath(gitRoot ?? thread.cwd, info.platformFamily)
      const stored = await this.registry.resolve(this.options.machineId, normalized)
      const updatedAt = secondsToMillis(thread.updatedAt)
      const existingProject = projectById.get(stored.projectId)
      projectById.set(stored.projectId, {
        projectId: stored.projectId,
        machineId: this.options.machineId,
        displayName: stored.displayName,
        canonicalRoot: stored.canonicalRoot,
        vcs: {
          kind: thread.gitInfo ? 'git' : 'none',
          originUrl: thread.gitInfo?.originUrl ?? existingProject?.vcs.originUrl ?? null,
          branch: thread.gitInfo?.branch ?? existingProject?.vcs.branch ?? null,
        },
        updatedAt: Math.max(updatedAt, existingProject?.updatedAt ?? 0),
      })
      threads.push(toSummary(this.options.machineId, stored.projectId, thread, isArchived))
    }

    const projects = [...projectById.values()].sort((left, right) => right.updatedAt - left.updatedAt)
    threads.sort((left, right) => right.recencyAt - left.recencyAt)
    const fingerprint = fingerprintCatalog(projects, threads, compatibility)
    const revision = await this.registry.commitFingerprint(fingerprint)
    const generatedAt = this.now()
    return {
      revision,
      generatedAt,
      machine: {
        machineId: this.options.machineId,
        displayName: this.options.machineDisplayName ?? hostname(),
        platformFamily: info.platformFamily,
        platformOs: info.platformOs,
        agentVersion: this.options.agentVersion,
        codexVersion: compatibility.detectedVersion,
        schemaHash: compatibility.schemaHash,
        runtimeWritable: compatibility.runtimeWritable,
        compatibilityReason: compatibility.reason,
        operations: compatibility.operations,
        lastSeenAt: generatedAt,
      },
      projects,
      threads,
    }
  }

  async threadDetail(threadId: string) {
    return normalizeThreadDetail(this.options.machineId, await this.client.readThread(threadId, true))
  }

  private async listAll(archived: boolean): Promise<CodexThread[]> {
    const output: CodexThread[] = []
    const seenCursors = new Set<string>()
    let cursor: string | null = null
    for (let pageNumber = 0; pageNumber < MAX_PAGES; pageNumber += 1) {
      const page = await this.client.listThreadsPage({
        cursor,
        limit: PAGE_LIMIT,
        archived,
        sourceKinds: SOURCE_KINDS,
        sortKey: 'recency_at',
        sortDirection: 'desc',
      })
      output.push(...page.data)
      if (!page.nextCursor) return output
      if (seenCursors.has(page.nextCursor)) throw new Error('thread/list returned a repeated cursor')
      seenCursors.add(page.nextCursor)
      cursor = page.nextCursor
    }
    throw new Error(`thread/list exceeded ${MAX_PAGES} pages`)
  }
}

function toSummary(
  machineId: string,
  projectId: string,
  thread: CodexThread,
  archived: boolean,
): CatalogThreadSummary {
  const source = sourceLabel(thread)
  return {
    machineId,
    threadId: thread.id,
    projectId,
    name: titleFor(thread),
    preview: thread.preview,
    createdAt: secondsToMillis(thread.createdAt),
    updatedAt: secondsToMillis(thread.updatedAt),
    recencyAt: secondsToMillis(thread.recencyAt ?? thread.updatedAt),
    archived,
    source,
    threadSource: thread.threadSource,
    parentThreadId: thread.parentThreadId,
    forkedFromId: thread.forkedFromId,
    isSubagent: thread.parentThreadId !== null || source === 'subAgent',
    isAutomation: thread.threadSource !== null && AUTOMATION_THREAD_SOURCES.has(thread.threadSource),
    runtimeState: runtimeState(thread),
    rawStatus: thread.status.type === 'unknown' ? (thread.status.rawType ?? 'unknown') : thread.status.type,
  }
}

function runtimeState(thread: CodexThread): CatalogRuntimeState {
  switch (thread.status.type) {
    case 'notLoaded':
    case 'unknown':
      return 'unknown'
    case 'idle':
      return 'idle'
    case 'systemError':
      return 'system_error'
    case 'active':
      if (thread.status.activeFlags.includes('waitingOnApproval')) return 'waiting_approval'
      if (thread.status.activeFlags.includes('waitingOnUserInput')) return 'waiting_user'
      return 'running'
  }
}

function sourceLabel(thread: CodexThread): string {
  if (typeof thread.source === 'string') return thread.source
  if ('custom' in thread.source) return `custom:${thread.source.custom}`
  return 'subAgent'
}

function titleFor(thread: CodexThread): string {
  const candidate = thread.name?.trim() || thread.preview.trim().split(/\r?\n/, 1)[0]
  return candidate || '未命名任务'
}

function secondsToMillis(value: number): number {
  return Math.round(value * 1_000)
}

async function detectRoots(
  cwds: string[],
  detector: (cwd: string) => Promise<string | null>,
  concurrency: number,
): Promise<Map<string, string | null>> {
  const output = new Map<string, string | null>()
  let cursor = 0
  async function worker(): Promise<void> {
    while (true) {
      const index = cursor
      cursor += 1
      const cwd = cwds[index]
      if (cwd === undefined) return
      output.set(cwd, await detector(cwd))
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, cwds.length) }, () => worker()))
  return output
}

function fingerprintCatalog(
  projects: CatalogProject[],
  threads: CatalogThreadSummary[],
  compatibility: CodexCompatibility,
): string {
  return createHash('sha256')
    .update(JSON.stringify({ projects, threads, compatibility }))
    .digest('hex')
}
