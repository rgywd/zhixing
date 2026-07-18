import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import nacl from 'tweetnacl'
import { afterEach, describe, expect, it } from 'vitest'
import { AgentHome } from '../config.js'
import { decryptWireBytes } from './crypto.js'
import { unwrapWireDataKey } from './keys.js'
import { WireRelayAgentClient } from './relayClient.js'
import type { WireEnvelope } from './types.js'

describe('WireRelayAgentClient', () => {
  const cleanup: Array<() => Promise<void>> = []
  afterEach(async () => {
    while (cleanup.length > 0) await cleanup.pop()?.()
  })

  it('authenticates, registers, wraps a per-payload key, and publishes encrypted catalog data', async () => {
    const recipient = nacl.box.keyPair()
    const envelopes: WireEnvelope[] = []
    const fixture = await startRelay(async (request, response, body) => {
      if (request.url === '/v1/auth/challenges') return json(response, {
        challengeId: 'challenge_1',
        challenge: Buffer.alloc(32, 7).toString('base64url'),
        expiresAt: Date.now() + 60_000,
      })
      if (request.url === '/v1/auth/verify') return json(response, {
        accountId: 'account_1',
        token: 't'.repeat(43),
        expiresAt: Date.now() + 60_000,
      })
      if (request.url === '/v1/devices' && request.method === 'POST') return json(response, { device: body })
      if (request.url === '/v1/devices') return json(response, { devices: [{
        deviceId: 'phone_1',
        publicKey: Buffer.from(recipient.publicKey).toString('base64url'),
        deviceType: 'android',
        lastSeenAt: Date.now(),
        revokedAt: null,
      }] })
      if (request.url === '/v1/envelopes') {
        envelopes.push(body as WireEnvelope)
        return json(response, { status: 'accepted', id: (body as WireEnvelope).id, deliveredTo: ['phone_1'] })
      }
      response.writeHead(404).end()
    })
    cleanup.push(fixture.close)
    const directory = await mkdtemp(join(tmpdir(), 'zhixing-wire-agent-'))
    cleanup.push(() => rm(directory, { recursive: true, force: true }))
    const home = new AgentHome(directory)
    const rootSecret = Buffer.alloc(32, 3).toString('base64url')

    await WireRelayAgentClient.login(home, rootSecret, fixture.origin)
    const published = await new WireRelayAgentClient(home).publish(
      'catalog.snapshot',
      { revision: 9, projects: [], threads: [] },
      'catalog_machine_1',
    )

    expect(published).toBe(1)
    expect(envelopes).toHaveLength(2)
    const [keyEnvelope, catalogEnvelope] = envelopes
    expect(keyEnvelope?.streamId).toBe('keys_phone_1')
    expect(catalogEnvelope?.streamId).toBe('catalog_machine_1_phone_1')
    const dataKey = unwrapWireDataKey(keyEnvelope!.cipherBundle, recipient.secretKey)
    expect(dataKey).not.toBeNull()
    const plaintext = decryptWireBytes(catalogEnvelope!.cipherBundle, dataKey!, catalogEnvelope!)
    expect(JSON.parse(Buffer.from(plaintext).toString('utf8'))).toMatchObject({
      type: 'catalog.snapshot',
      schema: 1,
      body: { revision: 9 },
    })
    expect(home.loadWireState().pending).toEqual({})
    expect(home.loadWireState().sequences.keys_phone_1).toBe(1)
  })
})

async function startRelay(
  handler: (request: IncomingMessage, response: ServerResponse, body: unknown) => Promise<void> | void,
): Promise<{ origin: string; close: () => Promise<void> }> {
  const server = createServer(async (request, response) => {
    const chunks: Buffer[] = []
    for await (const chunk of request) chunks.push(Buffer.from(chunk))
    const raw = Buffer.concat(chunks).toString('utf8')
    await handler(request, response, raw ? JSON.parse(raw) : null)
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  if (!address || typeof address === 'string') throw new Error('test relay did not bind')
  return {
    origin: `http://127.0.0.1:${address.port}`,
    close: () => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())),
  }
}

function json(response: ServerResponse, body: unknown): void {
  response.writeHead(200, { 'Content-Type': 'application/json' })
  response.end(JSON.stringify(body))
}
