import { hostname, homedir, platform } from 'node:os'
import { join } from 'node:path'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { randomUUID, randomBytes } from 'node:crypto'
import type { Credentials, MachineIdentity, MachineMetadata } from './types.js'

export const AGENT_VERSION = '0.1.0'
export const DEFAULT_SERVER_URL = 'https://api.cluster-fluster.com'

export function isClaudeP2Enabled(environment: NodeJS.ProcessEnv = process.env): boolean {
  return environment.ZHIXING_ENABLE_CLAUDE_P2 !== '0'
}

export interface AgentSettings {
  serverUrl: string
  machineId: string
  machineKey: string
}

export class AgentHome {
  readonly dir: string

  constructor(dir = process.env.ZHIXING_AGENT_HOME ?? join(homedir(), '.zhixing-agent')) {
    this.dir = dir
    mkdirSync(dir, { recursive: true, mode: 0o700 })
  }

  get clientId(): string {
    return `zhixing-agent/${AGENT_VERSION}`
  }

  loadSettings(): AgentSettings {
    const existing = this.readJson<Partial<AgentSettings>>('settings.json') ?? {}
    const settings: AgentSettings = {
      serverUrl: process.env.ZHIXING_RELAY_URL ?? existing.serverUrl ?? DEFAULT_SERVER_URL,
      machineId: existing.machineId ?? randomUUID(),
      machineKey: existing.machineKey ?? randomBytes(32).toString('base64'),
    }
    this.writeJson('settings.json', settings)
    return settings
  }

  machineIdentity(settings: AgentSettings): MachineIdentity {
    return { machineId: settings.machineId, machineKey: settings.machineKey }
  }

  loadCredentials(): Credentials | null {
    return this.readJson<Credentials>('credentials.json')
  }

  saveCredentials(credentials: Credentials): void {
    this.writeJson('credentials.json', credentials, 0o600)
  }

  machineMetadata(): MachineMetadata {
    return {
      host: hostname(),
      platform: platform(),
      happyCliVersion: AGENT_VERSION,
      homeDir: homedir(),
      happyHomeDir: this.dir,
      cliAvailability: {},
    }
  }

  private readJson<T>(name: string): T | null {
    try {
      return JSON.parse(readFileSync(join(this.dir, name), 'utf8')) as T
    } catch {
      return null
    }
  }

  private writeJson(name: string, value: unknown, mode = 0o644): void {
    writeFileSync(join(this.dir, name), `${JSON.stringify(value, null, 2)}\n`, { mode })
  }
}
