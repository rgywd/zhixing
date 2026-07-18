import Database from 'better-sqlite3'
import { createHash } from 'node:crypto'
import { chmod, mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadConfig } from '../config.js'
import { RELAY_SCHEMA_VERSION } from '../database.js'

export interface BackupManifest {
  schemaVersion: number
  createdAt: number
  sha256: string
  databaseFile: string
}

export async function backupDatabase(source: string, destination: string): Promise<BackupManifest> {
  await mkdir(path.dirname(destination), { recursive: true, mode: 0o700 })
  const database = new Database(source, { readonly: true, fileMustExist: true })
  try {
    assertHealthy(database)
    await database.backup(destination)
    await chmod(destination, 0o600)
  } finally {
    database.close()
  }
  const verify = new Database(destination, { fileMustExist: true })
  try {
    assertHealthy(verify)
    // 备份副本固定为单文件 DELETE journal，避免遗漏 WAL sidecar；恢复后服务会重新启用 WAL。
    verify.pragma('journal_mode = DELETE')
  } finally {
    verify.close()
  }
  const manifest: BackupManifest = {
    schemaVersion: RELAY_SCHEMA_VERSION,
    createdAt: Date.now(),
    sha256: createHash('sha256').update(await readFile(destination)).digest('hex'),
    databaseFile: path.basename(destination),
  }
  await writeFile(`${destination}.manifest.json`, `${JSON.stringify(manifest, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
  })
  return manifest
}

export function assertHealthy(database: Database.Database): void {
  const version = (
    database.prepare('SELECT schema_version FROM relay_meta WHERE singleton = 1').get() as
      | { schema_version: number }
      | undefined
  )?.schema_version
  if (version !== RELAY_SCHEMA_VERSION) {
    throw new Error(`backup schema ${String(version)} does not match ${RELAY_SCHEMA_VERSION}`)
  }
  const integrity = database.pragma('integrity_check', { simple: true })
  if (integrity !== 'ok') throw new Error(`backup integrity_check failed: ${String(integrity)}`)
}

async function main(): Promise<void> {
  const config = loadConfig()
  const timestamp = new Date().toISOString().replaceAll(/[:.]/g, '-')
  const destination = path.resolve(
    process.argv[2] ?? path.join(config.dataDir, 'backups', `relay-${timestamp}.sqlite3`),
  )
  const manifest = await backupDatabase(config.databasePath, destination)
  console.log(JSON.stringify({ destination, ...manifest }))
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  })
}
