import { spawn, type ChildProcess } from 'node:child_process'
import { execFileSync } from 'node:child_process'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import type { AgentHome } from '../config.js'
import { resolveCodexBinary } from '../codex/binary.js'
import { generateCodexSchemaHash } from '../codex/schemaHash.js'
import { loadOrCreateToken } from './secrets.js'

export interface AppServerProcessStatus {
  running: boolean
  pid: number | null
  loopbackUrl: string
  codexVersion: string | null
  schemaHash: string | null
  schemaState: 'pending' | 'ready' | 'failed'
  methods: string[]
}

export interface AppServerProcessOptions {
  port?: number
  codexBinary?: string
  spawnProcess?: typeof spawn
  healthCheck?: (origin: string) => Promise<boolean>
}

export class AppServerProcess {
  private child: ChildProcess | null = null
  private readonly port: number
  private readonly codexBinary: string
  private readonly spawnProcess: typeof spawn
  private readonly healthCheck: (origin: string) => Promise<boolean>
  private readonly tokenFile: string
  private readonly token: string
  private codexVersion: string | null = null
  private schemaHash: string | null = null
  private schemaState: AppServerProcessStatus['schemaState'] = 'pending'

  constructor(
    home: AgentHome,
    options: AppServerProcessOptions = {},
    private readonly logger: (message: string) => void = () => {},
  ) {
    this.port = options.port ?? Number(process.env.ZHIXING_APP_SERVER_PORT ?? 4500)
    if (!Number.isInteger(this.port) || this.port < 1 || this.port > 65_535) throw new Error('invalid app-server port')
    this.codexBinary = options.codexBinary ?? resolveCodexBinary()
    this.spawnProcess = options.spawnProcess ?? spawn
    this.healthCheck = options.healthCheck ?? defaultHealthCheck
    this.tokenFile = join(home.dir, 'app-server-token')
    this.token = loadOrCreateToken(this.tokenFile)
  }

  get loopbackUrl(): string {
    return `ws://127.0.0.1:${this.port}`
  }

  get bearerToken(): string {
    return this.token
  }

  async start(): Promise<void> {
    if (this.child && this.child.exitCode === null) return
    const child = this.spawnProcess(
      this.codexBinary,
      [
        'app-server',
        '--listen', this.loopbackUrl,
        '--ws-auth', 'capability-token',
        '--ws-token-file', this.tokenFile,
      ],
      { stdio: ['ignore', 'ignore', 'pipe'], windowsHide: true },
    )
    this.child = child
    child.stderr?.on('data', (chunk: Buffer) => {
      const message = chunk.toString('utf8').trim()
      if (message) this.logger(`[codex app-server] ${message.slice(0, 500)}`)
    })
    await this.waitUntilReady(child)
    this.refreshCompatibilityFacts()
  }

  async restart(): Promise<void> {
    await this.stop()
    await this.start()
  }

  async stop(): Promise<void> {
    const child = this.child
    this.child = null
    if (!child || child.exitCode !== null) return
    child.kill('SIGTERM')
    await Promise.race([
      new Promise<void>((resolve) => child.once('exit', () => resolve())),
      delay(5_000).then(() => {
        if (child.exitCode === null) child.kill('SIGKILL')
      }),
    ])
  }

  status(): AppServerProcessStatus {
    return {
      running: this.child !== null && this.child.exitCode === null,
      pid: this.child?.pid ?? null,
      loopbackUrl: this.loopbackUrl,
      codexVersion: this.codexVersion,
      schemaHash: this.schemaHash,
      schemaState: this.schemaState,
      methods: REQUIRED_METHODS,
    }
  }

  private async waitUntilReady(child: ChildProcess): Promise<void> {
    const origin = `http://127.0.0.1:${this.port}`
    for (let attempt = 0; attempt < 60; attempt += 1) {
      if (child.exitCode !== null) throw new Error(`codex app-server exited with code ${child.exitCode}`)
      if (await this.healthCheck(origin)) return
      await delay(250)
    }
    child.kill('SIGTERM')
    throw new Error('codex app-server readiness timed out')
  }

  private refreshCompatibilityFacts(): void {
    this.codexVersion = detectCodexVersion(this.codexBinary)
    this.schemaState = 'pending'
    void generateCodexSchemaHash(this.codexBinary).then((hash) => {
      this.schemaHash = hash
      this.schemaState = hash ? 'ready' : 'failed'
    })
  }
}

const REQUIRED_METHODS = [
  'thread/start',
  'thread/resume',
  'thread/read',
  'turn/start',
  'turn/steer',
  'turn/interrupt',
  'model/list',
  'permissionProfile/list',
  'skills/list',
]

async function defaultHealthCheck(origin: string): Promise<boolean> {
  try {
    const response = await fetch(`${origin}/readyz`, { signal: AbortSignal.timeout(1_000) })
    return response.ok
  } catch {
    return false
  }
}

function detectCodexVersion(binary: string): string | null {
  try {
    const output = execFileSync(binary, ['--version'], { encoding: 'utf8', windowsHide: true, timeout: 5_000 })
    return output.match(/(\d+\.\d+\.\d+)/)?.[1] ?? null
  } catch {
    return null
  }
}
