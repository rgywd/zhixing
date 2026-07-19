#!/usr/bin/env node
import { join } from 'node:path'
import { legacyLogin, runDaemon, waitForShutdownSignal } from './daemon.js'
import { AGENT_VERSION, AgentHome } from './config.js'
import { WireRelayAgentClient } from './wire/relayClient.js'
import { publishCatalogOnce } from './wire/catalogPublisher.js'

const USAGE = `zhixing-agent ${AGENT_VERSION} — 知行开发机代理

用法:
  zhixing-agent login <恢复密钥> [中继地址]  登录知行 Wire v1（0.2.0）
  zhixing-agent daemon             启动 Codex Wire 守护进程
  zhixing-agent direct-supervisor  仅启动 loopback App Server 与 supervisor
  zhixing-agent direct-config      显式输出本机 direct 配对凭据
  zhixing-agent wire-login <恢复密钥> [中继地址]  login 的兼容别名
  zhixing-agent legacy-login <恢复密钥>  登录 0.1.13 Happy 回滚链路
  zhixing-agent wire-sync          立即加密发布 Codex Project / Thread 目录
  zhixing-agent catalog            输出 Codex Project / Thread 目录诊断
  zhixing-agent version            显示版本

环境变量:
  ZHIXING_RELAY_URL     旧版 Happy 中继地址（仅回滚）
  ZHIXING_WIRE_RELAY_URL  Zhixing Relay v1 地址
  ZHIXING_AGENT_HOME    配置目录（默认 ~/.zhixing-agent）
  ZHIXING_ENABLE_LEGACY_HAPPY=1  临时启用 0.1.13 Happy 兼容服务
  ZHIXING_ENABLE_CLAUDE_P2=0  紧急关闭 Claude 通道（默认启用）
  ZHIXING_ENABLE_DIRECT_SUPERVISOR=0  紧急关闭 direct supervisor（默认启用）
  ZHIXING_APP_SERVER_PORT / ZHIXING_SUPERVISOR_PORT  loopback 监听端口
`

async function main(): Promise<void> {
  const [command, ...rest] = process.argv.slice(2)
  switch (command) {
    case 'login': {
      const key = rest[0]
      if (!key) {
        console.error('缺少恢复密钥参数')
        process.exitCode = 2
        return
      }
      const home = new AgentHome()
      const credentials = await WireRelayAgentClient.login(home, key, rest[1])
      console.log(`知行中继登录成功: ${credentials.deviceId} → ${credentials.serverUrl}`)
      return
    }
    case 'legacy-login': {
      const key = rest[0]
      if (!key) {
        console.error('缺少恢复密钥参数')
        process.exitCode = 2
        return
      }
      await legacyLogin(key)
      return
    }
    case 'daemon':
      await runDaemon()
      return
    case 'direct-supervisor': {
      const { CodexSupervisor } = await import('./supervisor/index.js')
      const supervisor = new CodexSupervisor(new AgentHome(), (message) => console.error(message))
      await supervisor.start()
      console.log('Codex direct supervisor 运行中（仅 loopback；Ctrl+C 退出）')
      await waitForShutdownSignal()
      await supervisor.stop()
      return
    }
    case 'direct-config': {
      const { CodexSupervisor } = await import('./supervisor/index.js')
      const supervisor = new CodexSupervisor(new AgentHome())
      console.log(JSON.stringify(supervisor.credentials, null, 2))
      return
    }
    case 'wire-login': {
      const key = rest[0]
      if (!key) {
        console.error('缺少恢复密钥参数')
        process.exitCode = 2
        return
      }
      const home = new AgentHome()
      const credentials = await WireRelayAgentClient.login(home, key, rest[1])
      console.log(`Wire Relay 登录成功: ${credentials.deviceId} → ${credentials.serverUrl}`)
      return
    }
    case 'wire-sync': {
      const result = await publishCatalogOnce(new AgentHome(), true)
      console.log(JSON.stringify(result, null, 2))
      return
    }
    case 'catalog':
      await printCatalog()
      return
    case 'version':
      console.log(AGENT_VERSION)
      return
    default:
      console.log(USAGE)
      if (command) process.exitCode = 2
  }
}

async function printCatalog(): Promise<void> {
  const [{ CodexAppServerClient }, { CodexCatalogService }, { ProjectRegistry }] = await Promise.all([
    import('./codex/appServerClient.js'),
    import('./catalog/codexCatalog.js'),
    import('./catalog/projectRegistry.js'),
  ])
  const home = new AgentHome()
  const settings = home.loadSettings()
  const client = new CodexAppServerClient(undefined, undefined, (message) => console.error(message))
  try {
    await client.start()
    const { generateCodexSchemaHash } = await import('./codex/schemaHash.js')
    const schemaHash = await generateCodexSchemaHash(client.binaryPath)
    const service = new CodexCatalogService(
      client,
      new ProjectRegistry(join(home.dir, 'codex-projects.json')),
      { machineId: settings.machineId, agentVersion: AGENT_VERSION, schemaHash },
    )
    console.log(JSON.stringify(await service.snapshot(), null, 2))
  } finally {
    client.stop()
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error)
  process.exitCode = 1
})
