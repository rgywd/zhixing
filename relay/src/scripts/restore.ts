import Database from 'better-sqlite3'
import { createHash, randomUUID } from 'node:crypto'
import { chmod, copyFile, mkdir, readFile, rename } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadConfig } from '../config.js'
import { assertHealthy, type BackupManifest } from './backup.js'

export async function restoreDatabase(
  backup: string,
  target: string,
): Promise<{ target: string; rollback: string | null }> {
  await verifyManifestIfPresent(backup)
  const source = new Database(backup, { readonly: true, fileMustExist: true })
  try {
    assertHealthy(source)
  } finally {
    source.close()
  }
  await mkdir(path.dirname(target), { recursive: true, mode: 0o700 })
  const temporary = `${target}.${randomUUID()}.restore`
  await copyFile(backup, temporary)
  await chmod(temporary, 0o600)
  const candidate = new Database(temporary, { readonly: true, fileMustExist: true })
  try {
    assertHealthy(candidate)
  } finally {
    candidate.close()
  }
  let rollback: string | null = null
  try {
    const suffix = new Date().toISOString().replaceAll(/[:.]/g, '-')
    rollback = `${target}.rollback-${suffix}`
    await rename(target, rollback)
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
    rollback = null
  }
  try {
    await rename(temporary, target)
  } catch (error) {
    if (rollback) await rename(rollback, target)
    throw error
  }
  return { target, rollback }
}

async function verifyManifestIfPresent(backup: string): Promise<void> {
  let manifest: BackupManifest
  try {
    manifest = JSON.parse(await readFile(`${backup}.manifest.json`, 'utf8')) as BackupManifest
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return
    throw error
  }
  const digest = createHash('sha256').update(await readFile(backup)).digest('hex')
  if (digest !== manifest.sha256) throw new Error('backup sha256 does not match manifest')
}

async function main(): Promise<void> {
  const [backup, flag] = process.argv.slice(2)
  if (!backup || flag !== '--confirm-stopped') {
    throw new Error('usage: npm run restore -- <backup.sqlite3> --confirm-stopped')
  }
  const config = loadConfig()
  console.log(JSON.stringify(await restoreDatabase(path.resolve(backup), config.databasePath)))
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  })
}
