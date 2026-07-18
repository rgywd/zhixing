import { describe, expect, it } from 'vitest'
import { analyzeCodexCompatibility, CODEX_SCHEMA_BASELINE } from './compatibility.js'
import { parseInitializeInfo, parseThread, parseThreadListPage } from './protocol.js'

describe('Codex protocol boundary', () => {
  it('parses the 0.144 thread contract and keeps unknown runtime status safe', () => {
    const thread = parseThread(rawThread({ status: { type: 'futureStatus', detail: 'kept-in-raw' } }))

    expect(thread.id).toBe('thread_1')
    expect(thread.status).toEqual({ type: 'unknown', rawType: 'futureStatus' })
    expect(thread.raw.status).toEqual({ type: 'futureStatus', detail: 'kept-in-raw' })
  })

  it('parses list cursors and rejects malformed timestamps', () => {
    expect(parseThreadListPage({ data: [rawThread()], nextCursor: 'next', backwardsCursor: null })).toMatchObject({
      nextCursor: 'next',
      data: [{ id: 'thread_1' }],
    })
    expect(() => parseThread(rawThread({ updatedAt: 'today' }))).toThrow(/updatedAt/)
  })

  it('allows runtime writes only for the reviewed Codex minor', () => {
    const current = parseInitializeInfo({
      userAgent: 'Codex Desktop/0.144.0 (Windows; x86_64)',
      codexHome: 'C:\\Users\\test\\.codex',
      platformFamily: 'windows',
      platformOs: 'windows',
    })
    expect(analyzeCodexCompatibility(current, CODEX_SCHEMA_BASELINE.schemaHash)).toMatchObject({
      detectedVersion: '0.144.0',
      catalogReadable: true,
      runtimeWritable: true,
    })
    expect(
      analyzeCodexCompatibility(
        { ...current, userAgent: 'Codex Desktop/0.145.0 (Windows; x86_64)' },
        CODEX_SCHEMA_BASELINE.schemaHash,
      ),
    ).toMatchObject({ runtimeWritable: false, operations: ['thread.list', 'thread.read'] })
    expect(analyzeCodexCompatibility(current, 'different')).toMatchObject({ runtimeWritable: false })
  })
})

function rawThread(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'thread_1',
    sessionId: 'session_1',
    forkedFromId: null,
    parentThreadId: null,
    preview: '继续做完 0.2.0',
    ephemeral: false,
    modelProvider: 'openai',
    createdAt: 1_700_000_000,
    updatedAt: 1_700_000_100,
    recencyAt: 1_700_000_100,
    status: { type: 'notLoaded' },
    path: null,
    cwd: 'C:\\Users\\test\\project',
    cliVersion: '0.144.0',
    source: 'vscode',
    threadSource: null,
    agentNickname: null,
    agentRole: null,
    gitInfo: { sha: null, branch: 'main', originUrl: null },
    name: '知行 0.2.0',
    turns: [],
    ...overrides,
  }
}
