import { describe, expect, it } from 'vitest'
import { HardLimits, normalizeRules } from './hardLimits.js'

describe('hard limits', () => {
  it('子串匹配命令（大小写不敏感）', () => {
    const limits = new HardLimits(['rm -rf', 'git push*--force'])
    expect(limits.matchCommand('sudo RM -RF /')?.rule).toBe('rm -rf')
    expect(limits.matchCommand('git push origin main --force-with-lease')?.rule).toBe('git push*--force')
    expect(limits.matchCommand('ls -la')).toBeNull()
  })

  it('path: 前缀只匹配文件路径', () => {
    const limits = new HardLimits(['path:*.env', 'path:secrets/*'])
    expect(limits.matchPath('config/.env')?.rule).toBe('path:*.env')
    expect(limits.matchPath('secrets/key.pem')).not.toBeNull()
    expect(limits.matchPath('src/main.ts')).toBeNull()
    expect(limits.matchCommand('cat .env')).toBeNull()
  })

  it('空行与注释被忽略', () => {
    const limits = new HardLimits(['', '  ', '# 注释', 'curl'])
    expect(limits.isEmpty).toBe(false)
    expect(limits.matchCommand('curl http://example.com')).not.toBeNull()
  })

  it('normalizeRules 接受数组与多行字符串', () => {
    expect(normalizeRules(['a', 'b'])).toEqual(['a', 'b'])
    expect(normalizeRules('a\nb\r\nc')).toEqual(['a', 'b', 'c'])
    expect(normalizeRules(42)).toEqual([])
  })
})
