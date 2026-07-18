import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { resolve, join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { AgentHome } from './config.js'
import { WireRelayAgentClient, type WirePayload } from './wire/relayClient.js'
import { WireCodexRuntimeBridge } from './wire/runtimeBridge.js'

async function main(): Promise<void> {
  if (process.env.ZHIXING_P5_SMOKE !== '1') {
    throw new Error('set ZHIXING_P5_SMOKE=1 to run the production runtime smoke')
  }
  const agentHome = new AgentHome()
  const credentials = agentHome.loadWireCredentials()
  if (!credentials) throw new Error('Wire Relay 尚未登录')
  const machineId = agentHome.loadSettings().machineId
  const phoneDirectory = await mkdtemp(join(tmpdir(), 'zhixing-p5-phone-'))
  const phoneHome = new AgentHome(phoneDirectory)
  const phoneCredentials = await WireRelayAgentClient.login(
    phoneHome,
    credentials.rootSecret,
    credentials.serverUrl,
    'android',
  )
  const phone = new WireRelayAgentClient(phoneHome, phoneCredentials)
  const agentRelay = new WireRelayAgentClient(agentHome)
  const bridge = new WireCodexRuntimeBridge(agentRelay, { machineId })
  let createdThreadId: string | null = null
  try {
    const startRequestId = opaqueId()
    await phone.publishToDevice(
      machineId,
      'runtime.command',
      {
        command: 'thread.start',
        machineId,
        cwd: resolve(process.cwd(), '..'),
        text: '这是知行 0.2.0 远程闭环测试。请只回复 ZX-P5-SMOKE-OK，不要调用工具或修改文件。',
      },
      `commands_${phoneCredentials.deviceId}_${machineId}`,
      startRequestId,
    )

    const received: WirePayload[] = []
    await waitFor(async () => {
      await bridge.pollOnce()
      received.push(...(await phone.poll(200)).payloads)
      const result = received.find((payload) => payload.type === 'command.result' && payload.requestId === startRequestId)
      createdThreadId = nestedString(result?.body, 'result', 'threadId') ?? createdThreadId
      return received.some((payload) =>
        payload.type === 'runtime.event' && nestedString(payload.body, 'type') === 'turn.completed',
      ) && received.some((payload) =>
        payload.type === 'runtime.event' && nestedString(payload.body, 'text')?.includes('ZX-P5-SMOKE-OK'),
      )
    }, 120_000)
    if (!createdThreadId) throw new Error('thread.start 未返回 threadId')

    const deleteRequestId = opaqueId()
    await phone.publishToDevice(
      machineId,
      'runtime.command',
      { command: 'thread.delete', machineId, threadId: createdThreadId },
      `commands_${phoneCredentials.deviceId}_${machineId}`,
      deleteRequestId,
    )
    await waitFor(async () => {
      await bridge.pollOnce()
      received.push(...(await phone.poll(200)).payloads)
      return received.some((payload) => {
        const body = payload.body as Record<string, unknown>
        return payload.type === 'command.result' && payload.requestId === deleteRequestId && body.ok === true
      })
    }, 30_000)
    createdThreadId = null

    console.log(JSON.stringify({
      ok: true,
      relay: credentials.serverUrl,
      machineId,
      threadCreated: true,
      receivedDelta: true,
      turnCompleted: true,
      threadDeleted: true,
      allEnvelopesAcknowledged: true,
    }))
  } finally {
    if (createdThreadId) {
      try {
        const cleanupId = opaqueId()
        await phone.publishToDevice(
          machineId,
          'runtime.command',
          { command: 'thread.delete', machineId, threadId: createdThreadId },
          `commands_${phoneCredentials.deviceId}_${machineId}`,
          cleanupId,
        )
        await bridge.pollOnce()
      } catch {}
    }
    await bridge.shutdown()
    await phone.revokeDevice(phoneCredentials.deviceId).catch(() => {})
    await rm(phoneDirectory, { recursive: true, force: true })
  }
}

async function waitFor(probe: () => Promise<boolean>, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await probe()) return
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 400))
  }
  throw new Error(`P5 smoke timed out after ${timeoutMs}ms`)
}

function nestedString(value: unknown, ...path: string[]): string | null {
  let cursor: unknown = value
  for (const key of path) {
    if (!cursor || typeof cursor !== 'object' || Array.isArray(cursor)) return null
    cursor = (cursor as Record<string, unknown>)[key]
  }
  return typeof cursor === 'string' ? cursor : null
}

function opaqueId(): string {
  return randomUUID().replaceAll('-', '_')
}

void main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error))
  process.exitCode = 1
})
