/**
 * 硬性限制：App 预设 disallowedTools 规则在 agent 层强制（补 Codex 无 deny list 的空白）。
 *
 * 规则语法（每条一行，来自预设「硬性限制」多行输入）：
 * - 纯文本 = 子串匹配（大小写不敏感），如 `rm -rf`
 * - 支持 `*` 通配，如 `git push*--force*`
 * - `path:` 前缀匹配文件改动路径，如 `path:*.env`；其余匹配 shell 命令
 */
export interface HardLimitHit {
  rule: string
}

export class HardLimits {
  private readonly commandRules: Array<{ raw: string; pattern: RegExp }>
  private readonly pathRules: Array<{ raw: string; pattern: RegExp }>

  constructor(rules: string[]) {
    const command: Array<{ raw: string; pattern: RegExp }> = []
    const path: Array<{ raw: string; pattern: RegExp }> = []
    for (const raw of rules) {
      const trimmed = raw.trim()
      if (!trimmed || trimmed.startsWith('#')) continue
      if (trimmed.toLowerCase().startsWith('path:')) {
        const body = trimmed.slice(5).trim()
        if (body) path.push({ raw: trimmed, pattern: compile(body) })
      } else {
        command.push({ raw: trimmed, pattern: compile(trimmed) })
      }
    }
    this.commandRules = command
    this.pathRules = path
  }

  get isEmpty(): boolean {
    return this.commandRules.length === 0 && this.pathRules.length === 0
  }

  matchCommand(commandText: string): HardLimitHit | null {
    for (const rule of this.commandRules) {
      if (rule.pattern.test(commandText)) return { rule: rule.raw }
    }
    return null
  }

  matchPath(pathText: string): HardLimitHit | null {
    for (const rule of this.pathRules) {
      if (rule.pattern.test(pathText)) return { rule: rule.raw }
    }
    return null
  }
}

/** 通配规则 → 不锚定的正则：`*` 匹配任意串，其余字符转义，大小写不敏感 */
function compile(rule: string): RegExp {
  const escaped = rule
    .split('*')
    .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .join('[\\s\\S]*')
  return new RegExp(escaped, 'i')
}

export function normalizeRules(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === 'string')
  if (typeof value === 'string') return value.split(/\r?\n/)
  return []
}
