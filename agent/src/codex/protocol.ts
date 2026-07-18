export interface CodexInitializeInfo {
  userAgent: string
  codexHome: string
  platformFamily: string
  platformOs: string
}

export type CodexThreadSource = string | { custom: string } | { subAgent: unknown }

export type CodexThreadStatus =
  | { type: 'notLoaded' }
  | { type: 'idle' }
  | { type: 'systemError' }
  | { type: 'active'; activeFlags: string[] }
  | { type: 'unknown'; rawType: string | null }

export interface CodexGitInfo {
  sha: string | null
  branch: string | null
  originUrl: string | null
}

export interface CodexThread {
  id: string
  sessionId: string
  forkedFromId: string | null
  parentThreadId: string | null
  preview: string
  ephemeral: boolean
  modelProvider: string
  createdAt: number
  updatedAt: number
  recencyAt: number | null
  status: CodexThreadStatus
  cwd: string
  cliVersion: string
  source: CodexThreadSource
  threadSource: string | null
  agentNickname: string | null
  agentRole: string | null
  gitInfo: CodexGitInfo | null
  name: string | null
  turns: unknown[]
  raw: Record<string, unknown>
}

export interface CodexThreadListPage {
  data: CodexThread[]
  nextCursor: string | null
  backwardsCursor: string | null
}

export interface CodexThreadListOptions {
  cursor?: string | null
  limit?: number
  archived?: boolean
  sourceKinds?: string[]
  sortKey?: 'created_at' | 'updated_at' | 'recency_at'
  sortDirection?: 'asc' | 'desc'
  useStateDbOnly?: boolean
}

export function parseInitializeInfo(value: unknown): CodexInitializeInfo {
  const record = requireRecord(value, 'initialize response')
  return {
    userAgent: requireString(record.userAgent, 'userAgent'),
    codexHome: requireString(record.codexHome, 'codexHome'),
    platformFamily: requireString(record.platformFamily, 'platformFamily'),
    platformOs: requireString(record.platformOs, 'platformOs'),
  }
}

export function parseThreadListPage(value: unknown): CodexThreadListPage {
  const record = requireRecord(value, 'thread/list response')
  if (!Array.isArray(record.data)) throw new TypeError('thread/list data must be an array')
  return {
    data: record.data.map(parseThread),
    nextCursor: optionalString(record.nextCursor, 'nextCursor'),
    backwardsCursor: optionalString(record.backwardsCursor, 'backwardsCursor'),
  }
}

export function parseThreadReadResponse(value: unknown): CodexThread {
  const record = requireRecord(value, 'thread/read response')
  return parseThread(record.thread)
}

export function parseThread(value: unknown): CodexThread {
  const record = requireRecord(value, 'thread')
  return {
    id: requireString(record.id, 'thread.id'),
    sessionId: requireString(record.sessionId, 'thread.sessionId'),
    forkedFromId: optionalString(record.forkedFromId, 'thread.forkedFromId'),
    parentThreadId: optionalString(record.parentThreadId, 'thread.parentThreadId'),
    preview: requireString(record.preview, 'thread.preview'),
    ephemeral: requireBoolean(record.ephemeral, 'thread.ephemeral'),
    modelProvider: requireString(record.modelProvider, 'thread.modelProvider'),
    createdAt: requireTimestamp(record.createdAt, 'thread.createdAt'),
    updatedAt: requireTimestamp(record.updatedAt, 'thread.updatedAt'),
    recencyAt: optionalTimestamp(record.recencyAt, 'thread.recencyAt'),
    status: parseThreadStatus(record.status),
    cwd: requireString(record.cwd, 'thread.cwd'),
    cliVersion: requireString(record.cliVersion, 'thread.cliVersion'),
    source: parseThreadSource(record.source),
    threadSource: optionalString(record.threadSource, 'thread.threadSource'),
    agentNickname: optionalString(record.agentNickname, 'thread.agentNickname'),
    agentRole: optionalString(record.agentRole, 'thread.agentRole'),
    gitInfo: parseGitInfo(record.gitInfo),
    name: optionalString(record.name, 'thread.name'),
    turns: Array.isArray(record.turns) ? record.turns : [],
    raw: record,
  }
}

function parseThreadStatus(value: unknown): CodexThreadStatus {
  const record = requireRecord(value, 'thread.status')
  const type = typeof record.type === 'string' ? record.type : null
  if (type === 'notLoaded' || type === 'idle' || type === 'systemError') return { type }
  if (type === 'active') {
    return {
      type,
      activeFlags: Array.isArray(record.activeFlags)
        ? record.activeFlags.filter((flag): flag is string => typeof flag === 'string')
        : [],
    }
  }
  return { type: 'unknown', rawType: type }
}

function parseThreadSource(value: unknown): CodexThreadSource {
  if (typeof value === 'string') return value
  const record = requireRecord(value, 'thread.source')
  if (typeof record.custom === 'string') return { custom: record.custom }
  if ('subAgent' in record) return { subAgent: record.subAgent }
  return 'unknown'
}

function parseGitInfo(value: unknown): CodexGitInfo | null {
  if (value == null) return null
  const record = requireRecord(value, 'thread.gitInfo')
  return {
    sha: optionalString(record.sha, 'thread.gitInfo.sha'),
    branch: optionalString(record.branch, 'thread.gitInfo.branch'),
    originUrl: optionalString(record.originUrl, 'thread.gitInfo.originUrl'),
  }
}

function requireRecord(value: unknown, field: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError(`${field} must be an object`)
  }
  return value as Record<string, unknown>
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== 'string') throw new TypeError(`${field} must be a string`)
  return value
}

function optionalString(value: unknown, field: string): string | null {
  if (value == null) return null
  return requireString(value, field)
}

function requireBoolean(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') throw new TypeError(`${field} must be a boolean`)
  return value
}

function requireTimestamp(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new TypeError(`${field} must be a non-negative timestamp`)
  }
  return value
}

function optionalTimestamp(value: unknown, field: string): number | null {
  if (value == null) return null
  return requireTimestamp(value, field)
}
