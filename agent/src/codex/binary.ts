import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'

export function resolveCodexBinary(environment: NodeJS.ProcessEnv = process.env): string {
  if (environment.ZHIXING_CODEX_BIN) return environment.ZHIXING_CODEX_BIN
  if (process.platform !== 'win32') return 'codex'

  const appData = environment.APPDATA
  if (appData) {
    const npmVendor = join(
      appData,
      'npm',
      'node_modules',
      '@openai',
      'codex',
      'node_modules',
      '@openai',
      'codex-win32-x64',
      'vendor',
      'x86_64-pc-windows-msvc',
      'bin',
      'codex.exe',
    )
    if (existsSync(npmVendor)) return npmVendor
  }

  try {
    const executable = execFileSync('where.exe', ['codex.exe'], {
      encoding: 'utf8',
      windowsHide: true,
      timeout: 5_000,
    })
      .split(/\r?\n/)
      .map((candidate) => candidate.trim())
      .find(Boolean)
    if (executable) return executable
  } catch {
    // The caller will surface the normal spawn error with installation guidance.
  }
  return 'codex.exe'
}
