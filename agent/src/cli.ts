#!/usr/bin/env node
import { login, runDaemon } from './daemon.js'
import { AGENT_VERSION } from './config.js'

const USAGE = `zhixing-agent ${AGENT_VERSION} — 知行开发机代理

用法:
  zhixing-agent login <恢复密钥>   使用与手机 App 相同的恢复密钥登录
  zhixing-agent daemon             启动守护进程（机器注册 + 中继连接）
  zhixing-agent version            显示版本

环境变量:
  ZHIXING_RELAY_URL     中继服务器地址（默认 Happy 官方中继，自托管后改为自己的）
  ZHIXING_AGENT_HOME    配置目录（默认 ~/.zhixing-agent）
  ZHIXING_ENABLE_CLAUDE_P2=0  紧急关闭 Claude 通道（默认启用）
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
      await login(key)
      return
    }
    case 'daemon':
      await runDaemon()
      return
    case 'version':
      console.log(AGENT_VERSION)
      return
    default:
      console.log(USAGE)
      if (command) process.exitCode = 2
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error)
  process.exitCode = 1
})
