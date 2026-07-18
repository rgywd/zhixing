import type { CodexThread } from '../codex/protocol.js'
import type { CatalogItem, CatalogThreadDetail, CatalogTurn } from './types.js'

export function normalizeThreadDetail(machineId: string, thread: CodexThread): CatalogThreadDetail {
  return {
    machineId,
    threadId: thread.id,
    turns: thread.turns.map(normalizeTurn).filter((turn): turn is CatalogTurn => turn !== null),
  }
}

function normalizeTurn(value: unknown): CatalogTurn | null {
  const turn = asRecord(value)
  if (!turn || typeof turn.id !== 'string') return null
  const error = asRecord(turn.error)
  return {
    turnId: turn.id,
    status: typeof turn.status === 'string' ? turn.status : 'unknown',
    startedAt: secondsToMillisOrNull(turn.startedAt),
    completedAt: secondsToMillisOrNull(turn.completedAt),
    durationMs: finiteNumberOrNull(turn.durationMs),
    error: typeof error?.message === 'string' ? error.message : null,
    items: Array.isArray(turn.items) ? turn.items.map(normalizeItem) : [],
  }
}

function normalizeItem(value: unknown): CatalogItem {
  const item = asRecord(value) ?? {}
  const rawType = typeof item.type === 'string' ? item.type : 'unknown'
  const itemId = typeof item.id === 'string' ? item.id : `opaque:${rawType}`
  switch (rawType) {
    case 'userMessage':
      return base(itemId, rawType, 'user', userMessageText(item.content), null, item)
    case 'agentMessage':
      return base(itemId, rawType, 'agent', stringOrNull(item.text), null, item)
    case 'plan':
      return base(itemId, rawType, 'agent', stringOrNull(item.text), null, item)
    case 'reasoning':
      return base(itemId, rawType, 'agent', stringArrayText(item.summary), null, item)
    case 'commandExecution':
      return base(itemId, rawType, 'tool', stringOrNull(item.command), stringOrNull(item.status), item)
    case 'fileChange':
      return base(itemId, rawType, 'tool', fileChangeText(item.changes), stringOrNull(item.status), item)
    case 'mcpToolCall':
    case 'dynamicToolCall':
    case 'collabAgentToolCall':
      return base(itemId, rawType, 'tool', toolTitle(item), stringOrNull(item.status), item)
    case 'contextCompaction':
    case 'enteredReviewMode':
    case 'exitedReviewMode':
      return base(itemId, rawType, 'system', stringOrNull(item.review), null, item)
    default:
      return base(itemId, 'opaque', 'unknown', null, null, item, rawType)
  }
}

function base(
  itemId: string,
  type: string,
  role: CatalogItem['role'],
  text: string | null,
  status: string | null,
  raw: Record<string, unknown>,
  rawType = type,
): CatalogItem {
  return { itemId, type, rawType, role, text, status, raw }
}

function userMessageText(value: unknown): string | null {
  if (!Array.isArray(value)) return null
  const parts = value.flatMap((entry) => {
    const content = asRecord(entry)
    if (!content) return []
    if (content.type === 'text' && typeof content.text === 'string') return [content.text]
    if (content.type === 'skill' && typeof content.name === 'string') return [`$${content.name}`]
    if (content.type === 'mention' && typeof content.name === 'string') return [`@${content.name}`]
    if (content.type === 'image' || content.type === 'localImage') return ['[图片]']
    return []
  })
  return parts.length > 0 ? parts.join('\n') : null
}

function fileChangeText(value: unknown): string | null {
  if (!Array.isArray(value)) return null
  const paths = value
    .map(asRecord)
    .filter((change): change is Record<string, unknown> => change !== null)
    .map((change) => change.path)
    .filter((filePath): filePath is string => typeof filePath === 'string')
  return paths.length > 0 ? paths.join('\n') : null
}

function toolTitle(item: Record<string, unknown>): string | null {
  const namespace = typeof item.namespace === 'string' ? `${item.namespace}.` : ''
  if (typeof item.tool === 'string') return `${namespace}${item.tool}`
  return null
}

function stringArrayText(value: unknown): string | null {
  if (!Array.isArray(value)) return stringOrNull(value)
  const parts = value.filter((part): part is string => typeof part === 'string')
  return parts.length > 0 ? parts.join('\n') : null
}

function stringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

function secondsToMillisOrNull(value: unknown): number | null {
  const number = finiteNumberOrNull(value)
  return number === null ? null : Math.round(number * 1_000)
}

function finiteNumberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null
}
