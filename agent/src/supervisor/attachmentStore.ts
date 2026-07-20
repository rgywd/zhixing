import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readdir, rename, rm, stat, writeFile } from 'node:fs/promises'
import { basename, join } from 'node:path'

export interface AttachmentReceipt {
  attachmentId: string
  localPath: string
  fileName: string
  mime: string
  size: number
  sha256: string
  expiresAt: number
}

export class AttachmentStore {
  private readonly receipts = new Map<string, AttachmentReceipt>()

  constructor(
    private readonly root: string,
    private readonly maxBytes = 25 * 1024 * 1024,
    private readonly ttlMs = 60 * 60_000,
  ) {}

  async initialize(now = Date.now()): Promise<void> {
    await mkdir(this.root, { recursive: true, mode: 0o700 })
    for (const entry of await readdir(this.root, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue
      const path = join(this.root, entry.name)
      const info = await stat(path).catch(() => null)
      if (!info || now - info.mtimeMs > this.ttlMs) await rm(path, { recursive: true, force: true })
    }
  }

  async put(
    body: Buffer,
    input: { fileName: string; mime: string; expectedSha256: string },
    now = Date.now(),
  ): Promise<AttachmentReceipt> {
    if (body.length < 1 || body.length > this.maxBytes) throw new Error('invalid_attachment_size')
    if (!/^[a-z0-9][a-z0-9.+-]*\/[a-z0-9][a-z0-9.+-]*$/i.test(input.mime)) {
      throw new Error('invalid_attachment_mime')
    }
    if (!/^[a-f0-9]{64}$/i.test(input.expectedSha256)) throw new Error('invalid_attachment_hash')
    const actualSha256 = createHash('sha256').update(body).digest('hex')
    if (actualSha256 !== input.expectedSha256.toLowerCase()) throw new Error('attachment_hash_mismatch')
    const fileName = sanitizeFileName(input.fileName)
    const attachmentId = randomUUID()
    const directory = join(this.root, attachmentId)
    const temporary = join(directory, `${fileName}.partial`)
    const localPath = join(directory, fileName)
    await mkdir(directory, { recursive: false, mode: 0o700 })
    try {
      await writeFile(temporary, body, { mode: 0o600, flag: 'wx' })
      await rename(temporary, localPath)
    } catch (error) {
      await rm(directory, { recursive: true, force: true })
      throw error
    }
    const receipt: AttachmentReceipt = {
      attachmentId,
      localPath,
      fileName,
      mime: input.mime.toLowerCase(),
      size: body.length,
      sha256: actualSha256,
      expiresAt: now + this.ttlMs,
    }
    this.receipts.set(attachmentId, receipt)
    return receipt
  }

  get(attachmentId: string, now = Date.now()): AttachmentReceipt | null {
    const receipt = this.receipts.get(attachmentId)
    if (!receipt || receipt.expiresAt <= now) return null
    return receipt
  }

  async remove(attachmentId: string): Promise<boolean> {
    const receipt = this.receipts.get(attachmentId)
    if (!receipt) return false
    this.receipts.delete(attachmentId)
    await rm(join(this.root, attachmentId), { recursive: true, force: true })
    return true
  }

  async sweep(now = Date.now()): Promise<void> {
    const expired = [...this.receipts.values()].filter((receipt) => receipt.expiresAt <= now)
    await Promise.all(expired.map((receipt) => this.remove(receipt.attachmentId)))
  }
}

function sanitizeFileName(value: string): string {
  const decoded = value.normalize('NFKC').replace(/[\u0000-\u001f\u007f]/g, '').trim()
  const leaf = basename(decoded).replace(/[<>:"/\\|?*]/g, '_').replace(/[. ]+$/g, '')
  if (!leaf || leaf === '.' || leaf === '..') return 'attachment.bin'
  return leaf.slice(0, 160)
}
