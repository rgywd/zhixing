import { afterEach, describe, expect, it } from 'vitest'
import { createServiceTimer } from './daemon.js'

const timers: NodeJS.Timeout[] = []

afterEach(() => {
  timers.splice(0).forEach(clearInterval)
})

describe('daemon lifecycle', () => {
  it('keeps service timers referenced so the agent remains alive', () => {
    const timer = createServiceTimer(() => {}, 60_000)
    timers.push(timer)

    expect(timer.hasRef()).toBe(true)
  })
})
