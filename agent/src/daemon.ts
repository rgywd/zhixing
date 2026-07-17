/**
 * zhixing-agent 守护进程：登录校验 → 机器注册 → 中继连接 → RPC 服务。
 * P1 提供 Codex；P2 在同一机器连接上增加 Claude Code 短进程适配器。
 */
import { execFileSync } from 'node:child_process'
import { AgentHome, isClaudeP2Enabled } from './config.js'
import { HappyApi } from './api.js'
import { MachineSocket } from './socket.js'
import type { SpawnParams, SpawnResult } from './types.js'

export interface SpawnDelegate {
  spawn(params: SpawnParams): Promise<SpawnResult>
  stopSession(sessionId: string): Promise<void>
  shutdown?(): Promise<void>
}

function cliAvailable(binary: string, versionArgs = ['--version']): boolean {
  try {
    execFileSync(binary, versionArgs, {
      stdio: 'ignore',
      timeout: 10_000,
    })
    return true
  } catch {
    return false
  }
}

export async function runDaemon(delegate?: SpawnDelegate): Promise<void> {
  const home = new AgentHome()
  const settings = home.loadSettings()
  const credentials = home.loadCredentials()
  if (!credentials) {
    throw new Error('尚未登录：先运行 `zhixing-agent login <恢复密钥>`')
  }

  if (!delegate) {
    const { AgentManager } = await import('./manager.js')
    delegate = new AgentManager({
      serverUrl: settings.serverUrl,
      clientId: home.clientId,
      machineId: settings.machineId,
      credentials,
      enableClaude: isClaudeP2Enabled(),
    })
  }

  const api = new HappyApi(settings.serverUrl, home.clientId)
  const identity = home.machineIdentity(settings)
  const metadata = home.machineMetadata()
  const claudeEnabled = isClaudeP2Enabled()
  metadata.cliAvailability = {
    codex: cliAvailable(process.env.ZHIXING_CODEX_BIN ?? 'codex'),
    claude: claudeEnabled && cliAvailable(process.env.ZHIXING_CLAUDE_BIN ?? 'claude'),
  }
  await api.registerMachine(credentials, identity, metadata)
  log(`机器已注册: ${identity.machineId} → ${settings.serverUrl}`)

  const socket = new MachineSocket({
    serverUrl: settings.serverUrl,
    clientId: home.clientId,
    credentials,
    identity,
    onConnectionChange: (connected) => log(connected ? '中继已连接' : '中继连接断开，自动重连中'),
  })

  socket.register('spawn-happy-session', async (params) => {
    const spawn = params as SpawnParams
    log(`收到 spawn 请求: ${spawn.directory} (agent=${spawn.agent ?? 'codex'})`)
    return delegate.spawn(spawn)
  })

  socket.register('stop-session', async (params) => {
    const sessionId = (params as { sessionId?: string }).sessionId
    if (sessionId && delegate) await delegate.stopSession(sessionId)
    return { ok: true }
  })

  socket.connect()
  log('守护进程运行中（Ctrl+C 退出）')
  await new Promise<void>((resolve) => {
    process.once('SIGINT', () => resolve())
    process.once('SIGTERM', () => resolve())
  })
  socket.close()
  await delegate.shutdown?.()
  log('守护进程已退出')
}

export async function login(recoveryKey: string): Promise<void> {
  const home = new AgentHome()
  const settings = home.loadSettings()
  const api = new HappyApi(settings.serverUrl, home.clientId)
  const credentials = await api.exchangeRecoveryKey(recoveryKey)
  home.saveCredentials(credentials)
  log(`登录成功，凭据已保存到 ${home.dir}（服务器: ${settings.serverUrl}）`)
}

export function log(message: string): void {
  console.log(`[zhixing-agent ${new Date().toISOString()}] ${message}`)
}
