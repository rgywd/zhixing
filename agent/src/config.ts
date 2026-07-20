import { hostname, homedir, platform } from 'node:os'
import { join } from 'node:path'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { randomUUID, randomBytes } from 'node:crypto'
import type { Credentials, MachineIdentity, MachineMetadata } from './types.js'

export const AGENT_VERSION = (JSON.parse(
  readFileSync(new URL('../package.json', import.meta.url), 'utf8'),
) as { version: string }).version
export const DEFAULT_SERVER_URL = 'https://api.cluster-fluster.com'
export const DEFAULT_WIRE_RELAY_URL = 'https://relay.8-208-118-119.sslip.io'

export interface WireAgentCredentials {
  serverUrl: string
  accountId: string
  deviceId: string
  token: string
  tokenExpiresAt: number
  rootSecret: string
}

export interface WireAgentState {
  sequences: Record<string, number>
  pending: Record<string, import('./wire/types.js').WireEnvelope>
  wrappedKeys: Record<string, string>
  acknowledgements: Record<string, number>
  requests: Record<string, {
    state: 'inflight' | 'completed'
    updatedAt: number
    result?: unknown
    error?: string
  }>
  lastCatalogRevision: number | null
  lastPublishedAt: number | null
}

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

  loadWireCredentials(): WireAgentCredentials | null {
    return this.readJson<WireAgentCredentials>('wire-credentials.json')
  }

  saveWireCredentials(credentials: WireAgentCredentials): void {
    this.writeJson('wire-credentials.json', credentials, 0o600)
  }

  loadWireState(): WireAgentState {
    const existing = this.readJson<Partial<WireAgentState>>('wire-state.json') ?? {}
    return {
      sequences: existing.sequences ?? {},
      pending: existing.pending ?? {},
      wrappedKeys: existing.wrappedKeys ?? {},
      acknowledgements: existing.acknowledgements ?? {},
      requests: existing.requests ?? {},
      lastCatalogRevision: existing.lastCatalogRevision ?? null,
      lastPublishedAt: existing.lastPublishedAt ?? null,
    }
  }

  saveWireState(state: WireAgentState): void {
    this.writeJson('wire-state.json', state, 0o600)
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
