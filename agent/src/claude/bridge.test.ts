import { afterEach, describe, expect, it, vi } from 'vitest'
import { ClaudeLocalBridge } from './bridge.js'

const bridges: ClaudeLocalBridge[] = []

afterEach(async () => {
  await Promise.all(bridges.splice(0).map((bridge) => bridge.close()))
})

describe('ClaudeLocalBridge', () => {
  it('只接受带随机 bearer 的 loopback 请求并分发 report', async () => {
    const report = vi.fn(async () => ({ backlog: ['补充'] }))
    const bridge = new ClaudeLocalBridge({
      report,
      ask: async () => ({ status: 'timeout' }),
      reportHtml: async () => {},
    })
    bridges.push(bridge)
    await bridge.start()
    expect(bridge.url).toMatch(/^http:\/\/127\.0\.0\.1:/)
    expect((await fetch(`${bridge.url}/mcp/report`, { method: 'POST' })).status).toBe(401)
    const response = await fetch(`${bridge.url}/mcp/report`, {
      method: 'POST',
      headers: { authorization: `Bearer ${bridge.bearerToken}`, 'content-type': 'application/json' },
      body: JSON.stringify({ text: '进展' }),
    })
    expect(response.status).toBe(200)
    expect(await response.json()).toEqual({ backlog: ['补充'] })
    expect(report).toHaveBeenCalledWith({ text: '进展' })
  })

  it('ask 返回处理结果', async () => {
    const bridge = new ClaudeLocalBridge({
      report: async () => ({ backlog: [] }),
      ask: async () => ({ status: 'answered', answer: '选 A' }),
      reportHtml: async () => {},
    })
    bridges.push(bridge)
    await bridge.start()
    const post = (path: string, body: unknown) =>
      fetch(`${bridge.url}${path}`, {
        method: 'POST',
        headers: { authorization: `Bearer ${bridge.bearerToken}`, 'content-type': 'application/json' },
        body: JSON.stringify(body),
      }).then((response) => response.json())
    await expect(post('/mcp/ask', { question: '选哪个？' })).resolves.toEqual({ status: 'answered', answer: '选 A' })
  })
})
