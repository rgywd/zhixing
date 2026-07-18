import Database from 'better-sqlite3'
import { chmodSync, mkdirSync, readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const RELAY_SCHEMA_VERSION = 1

export function openRelayDatabase(
  databasePath: string,
  migrationsDirectory = defaultMigrationsDirectory(),
): Database.Database {
  mkdirSync(path.dirname(databasePath), { recursive: true, mode: 0o700 })
  const database = new Database(databasePath)
  chmodSync(databasePath, 0o600)
  try {
    database.pragma('foreign_keys = ON')
    database.pragma('journal_mode = WAL')
    database.pragma('synchronous = FULL')
    database.pragma('busy_timeout = 5000')
    migrate(database, migrationsDirectory)
    return database
  } catch (error) {
    database.close()
    throw error
  }
}

function migrate(database: Database.Database, directory: string): void {
  let current = currentVersion(database)
  if (current > RELAY_SCHEMA_VERSION) {
    throw new Error(`relay database schema ${current} is newer than supported ${RELAY_SCHEMA_VERSION}`)
  }
  const migrations = readdirSync(directory)
    .filter((name) => /^\d+_.*\.sql$/.test(name))
    .map((name) => ({ name, version: Number(name.split('_', 1)[0]) }))
    .sort((left, right) => left.version - right.version)
  for (const migration of migrations) {
    if (migration.version <= current) continue
    if (migration.version !== current + 1) throw new Error(`missing relay migration ${current + 1}`)
    const sql = readFileSync(path.join(directory, migration.name), 'utf8')
    database.transaction(() => {
      database.exec(sql)
      database
        .prepare(
          `INSERT INTO relay_meta(singleton, schema_version, created_at) VALUES (1, ?, ?)
           ON CONFLICT(singleton) DO UPDATE SET schema_version = excluded.schema_version`,
        )
        .run(migration.version, Date.now())
    })()
    current = migration.version
  }
  if (current !== RELAY_SCHEMA_VERSION) {
    throw new Error(`relay database schema ${current} does not match required ${RELAY_SCHEMA_VERSION}`)
  }
  const integrity = database.pragma('quick_check', { simple: true })
  if (integrity !== 'ok') throw new Error(`relay database quick_check failed: ${String(integrity)}`)
}

function currentVersion(database: Database.Database): number {
  const exists = database
    .prepare("SELECT 1 AS present FROM sqlite_master WHERE type = 'table' AND name = 'relay_meta'")
    .get() as { present?: number } | undefined
  if (!exists) return 0
  const row = database.prepare('SELECT schema_version FROM relay_meta WHERE singleton = 1').get() as
    | { schema_version: number }
    | undefined
  return row?.schema_version ?? 0
}

function defaultMigrationsDirectory(): string {
  return path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'migrations')
}
