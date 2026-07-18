import { fileURLToPath } from 'node:url'

export interface ClaudeTelephoneConfig {
  mcpConfig: Record<string, unknown>
  appendSystemPrompt: string
  allowedTools: string[]
}

export function buildClaudeTelephoneConfig(): ClaudeTelephoneConfig {
  const mcpServer = fileURLToPath(new URL('./mcpServer.js', import.meta.url))
  return {
    mcpConfig: {
      mcpServers: {
        zhixing: { type: 'stdio', command: process.execPath, args: [mcpServer] },
      },
    },
    appendSystemPrompt:
      '你正在由知行手机端远程协作。请在关键阶段调用 mcp__zhixing__report 汇报进展并读取补充消息；需要关键业务决策时调用 mcp__zhixing__ask；适合结构化展示时可调用 mcp__zhixing__report_html。报告通道失败时继续当前任务，不要因此中断。',
    allowedTools: ['mcp__zhixing__report', 'mcp__zhixing__ask', 'mcp__zhixing__report_html'],
  }
}
