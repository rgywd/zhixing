import { randomBytes } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'

export function loadOrCreateToken(path: string): string {
  try {
    const existing = readFileSync(path, 'utf8').trim()
    if (existing.length >= 43 && !/\s/.test(existing)) return existing
  } catch {
    // Generate the file below.
  }
  const token = randomBytes(48).toString('base64url')
  writeFileSync(path, `${token}\n`, { encoding: 'utf8', mode: 0o600 })
  return token
}
