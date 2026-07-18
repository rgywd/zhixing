import { randomUUID } from 'node:crypto'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'
import type { CanonicalProjectPath } from './pathNormalization.js'

interface StoredProject {
  projectId: string
  machineId: string
  canonicalRoot: string
  lookupKey: string
  displayName: string
}

interface RegistryState {
  version: 1
  revision: number
  fingerprint: string | null
  projects: Record<string, StoredProject>
}

export class ProjectRegistry {
  private state: RegistryState | null = null

  constructor(private readonly stateFile: string) {}

  async resolve(machineId: string, projectPath: CanonicalProjectPath): Promise<StoredProject> {
    const state = await this.load()
    const key = registryKey(machineId, projectPath.lookupKey)
    const existing = state.projects[key]
    if (existing) return existing
    const created: StoredProject = {
      projectId: randomUUID(),
      machineId,
      canonicalRoot: projectPath.canonicalRoot,
      lookupKey: projectPath.lookupKey,
      displayName: projectPath.displayName,
    }
    state.projects[key] = created
    await this.save()
    return created
  }

  async commitFingerprint(fingerprint: string): Promise<number> {
    const state = await this.load()
    if (state.fingerprint !== fingerprint) {
      state.fingerprint = fingerprint
      state.revision += 1
      await this.save()
    }
    return state.revision
  }

  private async load(): Promise<RegistryState> {
    if (this.state) return this.state
    try {
      const parsed = JSON.parse(await readFile(this.stateFile, 'utf8')) as Partial<RegistryState>
      if (parsed.version !== 1 || typeof parsed.revision !== 'number' || !parsed.projects) {
        throw new Error('unsupported project registry')
      }
      this.state = {
        version: 1,
        revision: parsed.revision,
        fingerprint: typeof parsed.fingerprint === 'string' ? parsed.fingerprint : null,
        projects: parsed.projects,
      }
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code
      if (code !== 'ENOENT') throw error
      this.state = { version: 1, revision: 0, fingerprint: null, projects: {} }
    }
    return this.state
  }

  private async save(): Promise<void> {
    if (!this.state) return
    await mkdir(path.dirname(this.stateFile), { recursive: true })
    const temp = `${this.stateFile}.${process.pid}.tmp`
    await writeFile(temp, `${JSON.stringify(this.state, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 })
    await rename(temp, this.stateFile)
  }
}

function registryKey(machineId: string, lookupKey: string): string {
  return `${machineId}\u0000${lookupKey}`
}
