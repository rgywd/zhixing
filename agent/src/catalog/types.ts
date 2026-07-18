export type CatalogRuntimeState =
  | 'unknown'
  | 'idle'
  | 'running'
  | 'waiting_approval'
  | 'waiting_user'
  | 'system_error'

export interface CatalogMachine {
  machineId: string
  displayName: string
  platformFamily: string
  platformOs: string
  agentVersion: string
  codexVersion: string | null
  schemaHash: string | null
  runtimeWritable: boolean
  compatibilityReason: string | null
  operations: string[]
  lastSeenAt: number
}

export interface CatalogProject {
  projectId: string
  machineId: string
  displayName: string
  canonicalRoot: string
  vcs: {
    kind: 'git' | 'none'
    originUrl: string | null
    branch: string | null
  }
  updatedAt: number
}

export interface CatalogThreadSummary {
  machineId: string
  threadId: string
  projectId: string
  name: string
  preview: string
  createdAt: number
  updatedAt: number
  recencyAt: number
  archived: boolean
  source: string
  threadSource: string | null
  parentThreadId: string | null
  forkedFromId: string | null
  isSubagent: boolean
  isAutomation: boolean
  runtimeState: CatalogRuntimeState
  rawStatus: string
}

export interface CodexCatalogSnapshot {
  revision: number
  generatedAt: number
  machine: CatalogMachine
  projects: CatalogProject[]
  threads: CatalogThreadSummary[]
}

export interface CatalogThreadDetail {
  machineId: string
  threadId: string
  turns: CatalogTurn[]
}

export interface CatalogTurn {
  turnId: string
  status: string
  startedAt: number | null
  completedAt: number | null
  durationMs: number | null
  error: string | null
  items: CatalogItem[]
}

export interface CatalogItem {
  itemId: string
  type: string
  rawType: string
  role: 'user' | 'agent' | 'tool' | 'system' | 'unknown'
  text: string | null
  status: string | null
  raw: Record<string, unknown>
}
