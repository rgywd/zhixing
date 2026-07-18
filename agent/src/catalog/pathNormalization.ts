import { execFile } from 'node:child_process'
import path from 'node:path'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)

export interface CanonicalProjectPath {
  canonicalRoot: string
  lookupKey: string
  displayName: string
}

export function normalizeProjectPath(
  input: string,
  platformFamily: string,
): CanonicalProjectPath {
  if (!input.trim()) throw new TypeError('project path must not be empty')
  if (platformFamily === 'windows') return normalizeWindowsPath(input)
  const canonicalRoot = stripTrailingSeparator(path.posix.normalize(input.replaceAll('\\', '/')))
  return {
    canonicalRoot,
    lookupKey: canonicalRoot,
    displayName: path.posix.basename(canonicalRoot) || canonicalRoot,
  }
}

export async function detectGitRoot(cwd: string): Promise<string | null> {
  try {
    const { stdout } = await execFileAsync('git', ['-C', cwd, 'rev-parse', '--show-toplevel'], {
      timeout: 5_000,
      windowsHide: true,
    })
    const root = stdout.trim()
    return root || null
  } catch {
    return null
  }
}

function normalizeWindowsPath(input: string): CanonicalProjectPath {
  let value = input.trim().replace(/^\\\\\?\\/, '')
  const wslMount = value.match(/^\/mnt\/([a-zA-Z])(?:\/(.*))?$/)
  if (wslMount) value = `${wslMount[1]}:\\${(wslMount[2] ?? '').replaceAll('/', '\\')}`
  value = value.replaceAll('/', '\\')
  let canonicalRoot = stripTrailingSeparator(path.win32.normalize(value))
  canonicalRoot = canonicalRoot.replace(/^([a-z]):/, (_, drive: string) => `${drive.toUpperCase()}:`)
  const displayName = path.win32.basename(canonicalRoot) || canonicalRoot
  return {
    canonicalRoot,
    lookupKey: canonicalRoot.toLocaleLowerCase('en-US'),
    displayName,
  }
}

function stripTrailingSeparator(value: string): string {
  if (value === '/' || /^[A-Za-z]:\\$/.test(value)) return value
  return value.replace(/[\\/]+$/, '')
}
