import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { openRelayDatabase } from '../database.js'
import { backupDatabase } from './backup.js'
import { restoreDatabase } from './restore.js'

const temporaryDirectories: string[] = []

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })))
})

describe('relay backup and restore', () => {
  it('round-trips a WAL database with a checked manifest and rollback copy', async () => {
    const directory = await mkdtemp(path.join(tmpdir(), 'zhixing-relay-backup-'))
    temporaryDirectories.push(directory)
    const active = path.join(directory, 'active.sqlite3')
    const backup = path.join(directory, 'backups', 'relay.sqlite3')
    const database = openRelayDatabase(active)
    database
      .prepare('INSERT INTO accounts(account_id, auth_public_key, created_at) VALUES (?, ?, ?)')
      .run('account_1', Buffer.alloc(32, 1), 1)
    database.close()

    const manifest = await backupDatabase(active, backup)
    expect(manifest.sha256).toMatch(/^[0-9a-f]{64}$/)
    const changed = openRelayDatabase(active)
    changed.prepare('DELETE FROM accounts').run()
    changed.close()

    const restored = await restoreDatabase(backup, active)
    expect(restored.rollback).not.toBeNull()
    const verify = openRelayDatabase(active)
    expect((verify.prepare('SELECT COUNT(*) AS count FROM accounts').get() as { count: number }).count).toBe(1)
    verify.close()
  })
})
