import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { pathToFileURL } from 'node:url'
import * as z from 'zod/v4'

const server = new McpServer({ name: 'zhixing-claude-telephone', version: '0.1.0' })

server.registerTool(
  'report',
  {
    description: '向知行手机会话报告当前进展，并取回用户排队发送的补充消息。',
    inputSchema: { text: z.string().min(1).max(20_000) },
  },
  async ({ text }) => {
    try {
      const result = await bridgeRequest<{ backlog?: string[] }>('/mcp/report', { text })
      const backlog = result.backlog?.filter(Boolean) ?? []
      return textResult(backlog.length ? `已送达。用户补充：\n${backlog.join('\n\n')}` : '已送达，暂无用户补充。')
    } catch {
      return textResult('报告通道暂时不可用；继续当前任务，不要因此中断。')
    }
  },
)

server.registerTool(
  'ask',
  {
    description: '向知行手机端提出一个需要用户决策的问题，短暂等待回答；超时后结束本轮，回答会在下轮 resume 时提供。',
    inputSchema: {
      question: z.string().max(2_000).optional(),
      questions: z
        .array(z.object({ question: z.string().min(1).max(1_000), options: z.array(z.string().max(200)).max(8).optional() }))
        .min(1)
        .max(4)
        .optional(),
    },
  },
  async (input) => {
    try {
      const result = await bridgeRequest<{ status?: string; answer?: string }>('/mcp/ask', input)
      if (result.status === 'answered' && result.answer) return textResult(`用户回答：${result.answer}`)
      return textResult('等待超时。请安全结束本轮；用户回答会在下一轮 resume 时送达。')
    } catch {
      return textResult('询问通道暂时不可用。请安全结束本轮，不要自行猜测关键业务决策。')
    }
  },
)

server.registerTool(
  'report_html',
  {
    description: '向知行手机端发送结构化 HTML 报告卡片。',
    inputSchema: { html: z.string().min(1).max(512 * 1024), title: z.string().max(200).optional() },
  },
  async (input) => {
    try {
      await bridgeRequest('/mcp/report-html', input)
      return textResult('HTML 报告已送达。')
    } catch {
      return textResult('HTML 报告通道暂时不可用；继续当前任务，不要因此中断。')
    }
  },
)

async function bridgeRequest<T = unknown>(path: string, body: unknown): Promise<T> {
  const origin = process.env.ZHIXING_CLAUDE_BRIDGE_URL
  const token = process.env.ZHIXING_CLAUDE_BRIDGE_TOKEN
  if (!origin || !token) throw new Error('bridge_not_configured')
  const response = await fetch(`${origin}${path}`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(160_000),
  })
  if (!response.ok) throw new Error(`bridge_http_${response.status}`)
  return (await response.json()) as T
}

function textResult(text: string) {
  return { content: [{ type: 'text' as const, text }] }
}

async function main(): Promise<void> {
  await server.connect(new StdioServerTransport())
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) void main()
