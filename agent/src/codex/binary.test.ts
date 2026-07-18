import { describe, expect, it } from 'vitest'
import { resolveCodexBinary } from './binary.js'

describe('Codex binary resolution', () => {
  it('honors the explicit executable path without shell interpretation', () => {
    expect(resolveCodexBinary({ ZHIXING_CODEX_BIN: 'C:\\Tools\\codex.exe' })).toBe('C:\\Tools\\codex.exe')
  })
})
