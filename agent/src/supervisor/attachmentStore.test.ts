import { createHash } from 'node:crypto'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { AttachmentStore } from './attachmentStore.js'

let root: string

beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), 'zhixing-attachments-'))
})

afterEach(async () => {
  await rm(root, { recursive: true, force: true })
})

describe('AttachmentStore', () => {
  it('writes only beneath the controlled root and verifies the hash', async () => {
    const body = Buffer.from('screenshot')
    const hash = createHash('sha256').update(body).digest('hex')
    const store = new AttachmentStore(root)
    await store.initialize()

    const receipt = await store.put(body, {
      fileName: '../../screen.png',
      mime: 'image/png',
      expectedSha256: hash,
    })

    expect(receipt.localPath.startsWith(root)).toBe(true)
    expect(receipt.fileName).toBe('screen.png')
    expect(await readFile(receipt.localPath, 'utf8')).toBe('screenshot')
    expect(store.get(receipt.attachmentId)).toEqual(receipt)
  })

  it('rejects hash mismatch and removes only its generated directory', async () => {
    const store = new AttachmentStore(root)
    await store.initialize()

    await expect(store.put(Buffer.from('x'), {
      fileName: 'x.txt',
      mime: 'text/plain',
      expectedSha256: '0'.repeat(64),
    })).rejects.toThrow('attachment_hash_mismatch')
  })
})
