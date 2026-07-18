import { describe, expect, it } from 'vitest'
import { buildClaudeTelephoneConfig } from './integration.js'

describe('Claude telephone config', () => {
  it('MCP 电话配置不携带桥接密钥', () => {
    const config = buildClaudeTelephoneConfig()
    const serialized = JSON.stringify(config)
    expect(serialized).toContain('mcpServer.js')
    expect(serialized).not.toContain('ZHIXING_CLAUDE_BRIDGE_TOKEN')
    expect(config.appendSystemPrompt).toContain('mcp__zhixing__report')
    expect(config.allowedTools).toEqual([
      'mcp__zhixing__report',
      'mcp__zhixing__ask',
      'mcp__zhixing__report_html',
    ])
  })
})
