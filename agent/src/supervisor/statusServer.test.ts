import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SupervisorStatusServer } from './statusServer.js'

const TOKEN = 't'.repeat(64)
let server: SupervisorStatusServer
const restart = vi.fn(async () => {})

beforeEach(async () => {
  restart.mockClear()
  server = new SupervisorStatusServer(
    {
      status: async () => ({
        supervisorVersion: '0.2.2',
        appServer: { running: true, token: undefined },
      }),
      restartAppServer: restart,
    },
    TOKEN,
    0,
  )
  await server.start()
})

afterEach(async () => {
  await server.stop()
})

describe('SupervisorStatusServer', () => {
  it('binds loopback and requires bearer on every request', async () => {
    expect(server.loopbackOrigin).toMatch(/^http:\/\/127\.0\.0\.1:/)
    const rejected = await fetch(`${server.loopbackOrigin}/v1/status`)
    const accepted = await fetch(`${server.loopbackOrigin}/v1/status`, {
      headers: { authorization: `Bearer ${TOKEN}` },
    })

    expect(rejected.status).toBe(401)
    expect(accepted.status).toBe(200)
    const body = await accepted.json() as Record<string, unknown>
    expect(JSON.stringify(body)).not.toContain(TOKEN)
  })

  it('only restarts through the allowlisted authenticated endpoint', async () => {
    const missing = await fetch(`${server.loopbackOrigin}/v1/unknown`, {
      method: 'POST',
      headers: { authorization: `Bearer ${TOKEN}` },
    })
    const restarted = await fetch(`${server.loopbackOrigin}/v1/app-server/restart`, {
      method: 'POST',
      headers: { authorization: `Bearer ${TOKEN}` },
    })

    expect(missing.status).toBe(404)
    expect(restarted.status).toBe(200)
    expect(restart).toHaveBeenCalledTimes(1)
  })
})
