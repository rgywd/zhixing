import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { hashGeneratedSchemaDirectory } from './schemaHash.js'
import { CODEX_SCHEMA_BASELINE } from './compatibility.js'

describe('Codex schema hash', () => {
  it('is deterministic across creation order and includes relative paths', async () => {
    const first = await fixture([['v2/Thread.ts', 'thread'], ['index.ts', 'index']])
    const second = await fixture([['index.ts', 'index'], ['v2/Thread.ts', 'thread']])
    try {
      expect(await hashGeneratedSchemaDirectory(first)).toBe(await hashGeneratedSchemaDirectory(second))
      await writeFile(path.join(second, 'v2', 'Thread.ts'), 'changed')
      expect(await hashGeneratedSchemaDirectory(first)).not.toBe(await hashGeneratedSchemaDirectory(second))
    } finally {
      await Promise.all([rm(first, { recursive: true, force: true }), rm(second, { recursive: true, force: true })])
    }
  })

  it('keeps the write gate pinned to the reviewed 0_144 generated-schema fixture', async () => {
    const fixture = JSON.parse(await readFile(
      new URL('./fixtures/codex-app-server-0.144.json', import.meta.url),
      'utf8',
    )) as { codexVersion: string; fileCount: number; schemaHash: string; representativeFiles: string[] }

    expect(fixture.codexVersion).toBe('0.144.0')
    expect(fixture.fileCount).toBe(598)
    expect(fixture.representativeFiles).toContain('v2/TurnSteerParams.ts')
    expect(CODEX_SCHEMA_BASELINE.schemaHash).toBe(fixture.schemaHash)
  })
})

async function fixture(files: Array<[string, string]>): Promise<string> {
  const directory = await mkdtemp(path.join(tmpdir(), 'zhixing-schema-test-'))
  for (const [relative, content] of files) {
    const target = path.join(directory, relative)
    await mkdir(path.dirname(target), { recursive: true })
    await writeFile(target, content)
  }
  return directory
}
