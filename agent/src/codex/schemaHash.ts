import { execFile } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdtemp, readdir, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)

export async function generateCodexSchemaHash(codexBinary: string): Promise<string | null> {
  const directory = await mkdtemp(path.join(tmpdir(), 'zhixing-codex-schema-'))
  try {
    await execFileAsync(codexBinary, ['app-server', 'generate-ts', '--out', directory], {
      timeout: 60_000,
      windowsHide: true,
      maxBuffer: 10 * 1024 * 1024,
    })
    await delay(250)
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        return await hashGeneratedSchemaDirectory(directory)
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT' || attempt === 2) throw error
        await delay(100)
      }
    }
    return null
  } catch {
    return null
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
}

export async function hashGeneratedSchemaDirectory(directory: string): Promise<string> {
  const files = await listFiles(directory)
  const hash = createHash('sha256')
  for (const file of files.sort()) {
    const relative = path.relative(directory, file).replaceAll(path.sep, '/')
    hash.update(`${path.sep}${relative}\n`.replaceAll(path.sep, '/'), 'utf8')
    hash.update(await readFile(file))
  }
  return hash.digest('hex')
}

async function listFiles(directory: string): Promise<string[]> {
  const output: string[] = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name)
    if (entry.isDirectory()) output.push(...(await listFiles(absolute)))
    else if (entry.isFile()) output.push(absolute)
  }
  return output
}
