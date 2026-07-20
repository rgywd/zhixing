import { describe, expect, it } from 'vitest'
import { probeTailscale } from './tailscaleProbe.js'

describe('probeTailscale', () => {
  it('reports a missing binary without throwing', async () => {
    const result = await probeTailscale('definitely-missing-tailscale-binary')

    expect(result.installed).toBe(false)
    expect(result.serveConfigured).toBe(false)
    expect(result.error).toBeTruthy()
  })
})
