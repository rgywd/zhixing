import { timingSafeEqual } from 'node:crypto'
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'

export interface SupervisorStatusProvider {
  status(): Promise<Record<string, unknown>>
  restartAppServer(): Promise<void>
  uploadAttachment?(input: {
    body: Buffer
    fileName: string
    mime: string
    sha256: string
  }): Promise<object>
  deleteAttachment?(attachmentId: string): Promise<boolean>
}

export class SupervisorStatusServer {
  private server: Server | null = null
  private origin = ''
  private lastRestartAt = 0

  constructor(
    private readonly provider: SupervisorStatusProvider,
    private readonly token: string,
    private readonly port = Number(process.env.ZHIXING_SUPERVISOR_PORT ?? 4642),
  ) {
    if (!Number.isInteger(port) || port < 0 || port > 65_535) throw new Error('invalid supervisor port')
    if (token.length < 43 || /\s/.test(token)) throw new Error('invalid supervisor bearer token')
  }

  get loopbackOrigin(): string {
    if (!this.origin) throw new Error('supervisor status server is not running')
    return this.origin
  }

  get configuredLoopbackOrigin(): string {
    return this.origin || `http://127.0.0.1:${this.port}`
  }

  async start(): Promise<void> {
    if (this.server) return
    const server = createServer((request, response) => void this.route(request, response))
    await new Promise<void>((resolve, reject) => {
      server.once('error', reject)
      server.listen(this.port, '127.0.0.1', () => {
        server.off('error', reject)
        resolve()
      })
    })
    const address = server.address()
    if (!address || typeof address === 'string') {
      server.close()
      throw new Error('supervisor failed to allocate a loopback port')
    }
    this.server = server
    this.origin = `http://127.0.0.1:${address.port}`
  }

  async stop(): Promise<void> {
    const server = this.server
    this.server = null
    this.origin = ''
    if (!server) return
    await new Promise<void>((resolve) => server.close(() => resolve()))
  }

  private async route(request: IncomingMessage, response: ServerResponse): Promise<void> {
    response.setHeader('cache-control', 'no-store')
    response.setHeader('x-content-type-options', 'nosniff')
    if (!authorized(request.headers.authorization, this.token)) {
      return send(response, 401, { error: 'unauthorized' })
    }
    try {
      if (request.method === 'GET' && request.url === '/v1/status') {
        return send(response, 200, await this.provider.status())
      }
      if (request.method === 'POST' && request.url === '/v1/app-server/restart') {
        const now = Date.now()
        if (now - this.lastRestartAt < 10_000) return send(response, 429, { error: 'restart_rate_limited' })
        this.lastRestartAt = now
        await this.provider.restartAppServer()
        return send(response, 200, { ok: true })
      }
      if (request.method === 'POST' && request.url === '/v1/attachments' && this.provider.uploadAttachment) {
        if (!this.allowWrite()) return send(response, 429, { error: 'write_rate_limited' })
        const fileNameHeader = request.headers['x-zhixing-file-name']
        const expectedSha256 = request.headers['x-content-sha256']
        const mime = request.headers['content-type']
        if (typeof fileNameHeader !== 'string' || typeof expectedSha256 !== 'string' || typeof mime !== 'string') {
          return send(response, 400, { error: 'missing_attachment_headers' })
        }
        const body = await readBody(request, MAX_ATTACHMENT_BYTES)
        const fileName = Buffer.from(fileNameHeader, 'base64url').toString('utf8')
        return send(response, 201, await this.provider.uploadAttachment({ body, fileName, mime, sha256: expectedSha256 }))
      }
      const attachmentMatch = request.url?.match(/^\/v1\/attachments\/([0-9a-f-]{36})$/i)
      if (request.method === 'DELETE' && attachmentMatch && this.provider.deleteAttachment) {
        if (!this.allowWrite()) return send(response, 429, { error: 'write_rate_limited' })
        const removed = await this.provider.deleteAttachment(attachmentMatch[1])
        return send(response, removed ? 200 : 404, removed ? { ok: true } : { error: 'not_found' })
      }
      return send(response, 404, { error: 'not_found' })
    } catch {
      return send(response, 503, { error: 'service_unavailable' })
    }
  }

  private readonly writeRequests: number[] = []

  private allowWrite(now = Date.now()): boolean {
    while (this.writeRequests[0] !== undefined && now - this.writeRequests[0] > 60_000) this.writeRequests.shift()
    if (this.writeRequests.length >= 30) return false
    this.writeRequests.push(now)
    return true
  }
}

function authorized(header: string | undefined, token: string): boolean {
  if (!header?.startsWith('Bearer ')) return false
  const actual = Buffer.from(header.slice('Bearer '.length))
  const expected = Buffer.from(token)
  return actual.length === expected.length && timingSafeEqual(actual, expected)
}

function send(response: ServerResponse, status: number, body: unknown): void {
  if (response.headersSent) return
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8' })
  response.end(JSON.stringify(body))
}

async function readBody(request: IncomingMessage, maxBytes: number): Promise<Buffer> {
  const contentLength = Number(request.headers['content-length'] ?? 0)
  if (!Number.isSafeInteger(contentLength) || contentLength < 1 || contentLength > maxBytes) {
    throw new Error('invalid_attachment_size')
  }
  const chunks: Buffer[] = []
  let total = 0
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
    total += buffer.length
    if (total > maxBytes) throw new Error('invalid_attachment_size')
    chunks.push(buffer)
  }
  if (total !== contentLength) throw new Error('attachment_length_mismatch')
  return Buffer.concat(chunks)
}

const MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
