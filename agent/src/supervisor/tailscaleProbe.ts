import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)

export interface TailscaleStatus {
  installed: boolean
  backendState: string | null
  dnsName: string | null
  tailnetName: string | null
  serveConfigured: boolean
  error: string | null
}

export async function probeTailscale(binary = 'tailscale'): Promise<TailscaleStatus> {
  try {
    const { stdout } = await execFileAsync(binary, ['status', '--json'], {
      encoding: 'utf8',
      windowsHide: true,
      timeout: 5_000,
      maxBuffer: 2 * 1024 * 1024,
    })
    const status = JSON.parse(stdout) as {
      BackendState?: string
      Self?: { DNSName?: string }
      CurrentTailnet?: { Name?: string }
    }
    let serveConfigured = false
    try {
      const serve = await execFileAsync(binary, ['serve', 'status', '--json'], {
        encoding: 'utf8',
        windowsHide: true,
        timeout: 5_000,
        maxBuffer: 2 * 1024 * 1024,
      })
      const parsed = JSON.parse(serve.stdout) as Record<string, unknown>
      serveConfigured = Object.keys(parsed).length > 0
    } catch {
      // Logged-in Tailscale without Serve is a valid state.
    }
    return {
      installed: true,
      backendState: status.BackendState ?? null,
      dnsName: status.Self?.DNSName?.replace(/\.$/, '') ?? null,
      tailnetName: status.CurrentTailnet?.Name ?? null,
      serveConfigured,
      error: null,
    }
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code
    return {
      installed: code !== 'ENOENT',
      backendState: null,
      dnsName: null,
      tailnetName: null,
      serveConfigured: false,
      error: error instanceof Error ? error.message : String(error),
    }
  }
}
