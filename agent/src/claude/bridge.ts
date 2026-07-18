import { randomBytes } from 'node:crypto'
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'

const MAX_BODY_BYTES = 1024 * 1024

export interface ClaudeAskRequest {
  question?: string
  questions?: Array<{ question: string; options?: string[] }>
}

export interface ClaudeBridgeHandlers {
  report(input: { text: string }): Promise<{ backlog: string[] }>
  ask(input: ClaudeAskRequest): Promise<{ status: 'answered' | 'timeout'; answer?: string }>
  reportHtml(input: { html: string; title?: string }): Promise<void>
}

export class ClaudeLocalBridge {
  private server: Server | null = null
  private readonly token = randomBytes(32).toString('base64url')
  private origin = ''

  constructor(private readonly handlers: ClaudeBridgeHandlers) {}

  get url(): string {
    if (!this.origin) throw new Error('Claude local bridge has not started')
    return this.origin
  }

  get bearerToken(): string {
    return this.token
  }

  async start(): Promise<void> {
    if (this.server) return
    const server = createServer((request, response) => void this.route(request, response))
    await new Promise<void>((resolve, reject) => {
      server.once('error', reject)
      server.listen(0, '127.0.0.1', () => {
        server.off('error', reject)
        resolve()
      })
    })
    const address = server.address()
    if (!address || typeof address === 'string') {
      server.close()
      throw new Error('Claude local bridge failed to allocate a loopback port')
    }
    this.server = server
    this.origin = `http://127.0.0.1:${address.port}`
  }

  async close(): Promise<void> {
    const server = this.server
    this.server = null
    this.origin = ''
    if (!server) return
    await new Promise<void>((resolve) => server.close(() => resolve()))
  }

  private async route(request: IncomingMessage, response: ServerResponse): Promise<void> {
    try {
      if (request.method !== 'POST') return send(response, 405, { error: 'method_not_allowed' })
      if (request.headers.authorization !== `Bearer ${this.token}`) {
        return send(response, 401, { error: 'unauthorized' })
      }
      const input = await readJson(request)
      switch (request.url) {
        case '/mcp/report':
          return send(response, 200, await this.handlers.report(input as { text: string }))
        case '/mcp/ask':
          return send(response, 200, await this.handlers.ask(input as ClaudeAskRequest))
        case '/mcp/report-html':
          await this.handlers.reportHtml(input as { html: string; title?: string })
          return send(response, 200, { ok: true })
        default:
          return send(response, 404, { error: 'not_found' })
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      send(response, message === 'body_too_large' ? 413 : 400, { error: message })
    }
  }
}

async function readJson(request: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = []
  let total = 0
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
    total += buffer.length
    if (total > MAX_BODY_BYTES) throw new Error('body_too_large')
    chunks.push(buffer)
  }
  const text = Buffer.concat(chunks).toString('utf8')
  return text ? JSON.parse(text) : {}
}

function send(response: ServerResponse, status: number, body: unknown): void {
  if (response.headersSent) return
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8' })
  response.end(JSON.stringify(body))
}
