import Database from 'better-sqlite3'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { openRelayDatabase, RELAY_SCHEMA_VERSION } from './database.js'

const temporaryDirectories: string[] = []

afterEach(async () => {
  await Promise.all(
    temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })),
  )
})

describe('relay database migrations', () => {
  it('enables WAL, foreign keys, and the exact supported schema', async () => {
    const file = await databaseFile()
    const database = openRelayDatabase(file)
    expect(database.pragma('journal_mode', { simple: true })).toBe('wal')
    expect(database.pragma('foreign_keys', { simple: true })).toBe(1)
    expect(
      (
        database.prepare('SELECT schema_version FROM relay_meta WHERE singleton = 1').get() as {
          schema_version: number
        }
      ).schema_version,
    ).toBe(RELAY_SCHEMA_VERSION)
    database.close()
  })

  it('refuses to start on a newer database instead of silently downgrading', async () => {
    const file = await databaseFile()
    const database = openRelayDatabase(file)
    database.prepare('UPDATE relay_meta SET schema_version = 99 WHERE singleton = 1').run()
    database.close()
    expect(() => openRelayDatabase(file)).toThrow(/newer than supported/)
  })
})

async function databaseFile(): Promise<string> {
  const directory = await mkdtemp(path.join(tmpdir(), 'zhixing-relay-db-'))
  temporaryDirectories.push(directory)
  return path.join(directory, 'relay.sqlite3')
}
