import { randomBytes, randomUUID } from 'node:crypto'
import { readFile, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { io, type Socket } from 'socket.io-client'
import { HappyApi, secretOf } from './api.js'
import { AgentManager } from './manager.js'
import { MachineSocket } from './socket.js'
import { decryptRecord, encryptRecord, unwrapDataKey } from './crypto.js'
import type { Credentials, SessionHandle } from './types.js'

const relay = required('ZHIXING_RELAY_URL').replace(/\/$/, '')
const recoveryKey = required('ZHIXING_SMOKE_RECOVERY_KEY')
const directory = process.env.ZHIXING_SMOKE_DIRECTORY || process.cwd()
const clientId = 'zhixing-p2-smoke/1'
const marker = `P2-${Date.now().toString(36).toUpperCase()}`
const approvalProbe = join(directory, `.zhixing-${marker}-approval.tmp`)
const fullAccessProbe = join(directory, `.zhixing-${marker}-full-access.tmp`)
const api = new HappyApi(relay, clientId)

interface RawSession {
  id: string
  seq: number
  metadata: string
  agentState?: string | null
  dataEncryptionKey?: string | null
}

async function main(): Promise<void> {
  const credentials = await api.exchangeRecoveryKey(recoveryKey)
  const identity = {
    machineId: randomUUID(),
    machineKey: randomBytes(32).toString('base64'),
  }
  await api.registerMachine(credentials, identity, {
    host: 'zhixing-p2-smoke',
    platform: process.platform,
    happyCliVersion: 'p2-smoke',
    homeDir: directory,
    happyHomeDir: 'ephemeral',
    cliAvailability: { codex: false, claude: true },
  })

  const manager = new AgentManager({
    serverUrl: relay,
    clientId,
    machineId: identity.machineId,
    credentials,
    enableClaude: true,
  })
  let machineConnectionCount = 0
  const machineSocket = new MachineSocket({
    serverUrl: relay,
    clientId,
    credentials,
    identity,
    onConnectionChange: (value) => { if (value) machineConnectionCount += 1 },
  })
  machineSocket.register('spawn-happy-session', (params) => manager.spawn(params as never))
  machineSocket.register('stop-session', async (params) => {
    await manager.stopSession(String((params as { sessionId?: string }).sessionId ?? ''))
    return { ok: true }
  })
  const userSocket = createUserSocket(credentials)
  try {
    machineSocket.connect()
    await Promise.all([
      poll('machine socket connection', async () => machineConnectionCount > 0 ? true : null, 20_000),
      connect(userSocket),
    ])
    const normal = await spawn(userSocket, credentials, identity.machineId, identity.machineKey, 'default')
    await runTelephoneLoop(normal, credentials)
    await runResumeLoop(normal, credentials)
    await runApprovalLoop(userSocket, normal, credentials)

    const previousConnectionCount = machineConnectionCount
    machineSocket.close()
    await new Promise((resolve) => setTimeout(resolve, 250))
    machineSocket.connect()
    await poll(
      'machine socket reconnection',
      async () => machineConnectionCount > previousConnectionCount ? true : null,
      20_000,
    )

    const bypass = await spawn(userSocket, credentials, identity.machineId, identity.machineKey, 'bypassPermissions')
    await runBypassLoop(bypass, credentials)
    await manager.stopSession(normal.id)
    await manager.stopSession(bypass.id)
    console.log(JSON.stringify({
      ok: true,
      relay,
      marker,
      normalSessionId: normal.id,
      bypassSessionId: bypass.id,
      checks: ['report', 'ask-answer', 'report-html', 'resume', 'default-approval', 'machine-reconnect', 'bypass-permissions'],
    }))
  } finally {
    userSocket.disconnect()
    machineSocket.close()
    await manager.shutdown()
    await Promise.all([
      rm(approvalProbe, { force: true }),
      rm(fullAccessProbe, { force: true }),
    ])
  }
}

async function runTelephoneLoop(session: SessionHandle, credentials: Credentials): Promise<void> {
  await send(session, credentials, [
    'This is an automated integration smoke. Use only the zhixing MCP tools and follow every step.',
    `1. Call mcp__zhixing__report with text "REPORT-${marker}".`,
    `2. Call mcp__zhixing__ask with one question: header "Choice", question "Select the smoke answer", options ["A", "B"], multiSelect false.`,
    `3. After the answer, call mcp__zhixing__report_html with title "HTML-${marker}" and html "<h1>HTML-${marker}</h1><p>answer received</p>".`,
    `4. Finish by replying "DONE-${marker}".`,
  ].join('\n'), 'default')

  const ask = await waitForEvent(session, credentials, (event) => event.kind === 'claude-ask', 'claude ask')
  assert(Array.isArray(ask.questions), 'ask did not contain structured questions')
  await send(session, credentials, 'Choice：A', 'default')
  await waitForEvent(session, credentials, (event) => event.kind === 'claude-report-html' && event.title === `HTML-${marker}`, 'HTML report')
  await waitForEvent(session, credentials, (event) => event.t === 'text' && String(event.text).includes(`DONE-${marker}`), 'turn completion text')
}

async function runResumeLoop(session: SessionHandle, credentials: Credentials): Promise<void> {
  await send(
    session,
    credentials,
    `This is the second process turn. Reply exactly "RESUME-${marker}:A" using the answer selected in the previous turn. Do not use tools.`,
    'default',
  )
  await waitForEvent(
    session,
    credentials,
    (event) => event.t === 'text' && String(event.text).includes(`RESUME-${marker}:A`),
    'Claude transcript resume',
  )
}

async function runApprovalLoop(socket: Socket, session: SessionHandle, credentials: Credentials): Promise<void> {
  await send(
    session,
    credentials,
    [
      `You MUST use the Write tool to create ${JSON.stringify(approvalProbe)}.`,
      `Write exactly "APPROVAL-${marker}" to that file. Do not use Bash and do not merely claim success.`,
      `Only after the Write tool succeeds reply exactly "APPROVED-${marker}".`,
    ].join('\n'),
    'default',
  )
  const request = await waitForApproval(session, credentials)
  const answer = await rpc(socket, `${session.id}:permission`, {
    id: request.id,
    approved: true,
    decision: 'approved',
  }, session.encryptionKey)
  assert((answer as { ok?: boolean }).ok === true, 'permission RPC was not accepted')
  await waitForEvent(
    session,
    credentials,
    (event) => event.t === 'text' && String(event.text).includes(`APPROVED-${marker}`),
    'approved tool completion',
  )
  assert((await readFile(approvalProbe, 'utf8')).trim() === `APPROVAL-${marker}`, 'approved Write did not create the probe')
}

async function runBypassLoop(session: SessionHandle, credentials: Credentials): Promise<void> {
  await send(
    session,
    credentials,
    [
      `You MUST use the Write tool to create ${JSON.stringify(fullAccessProbe)}.`,
      `Write exactly "FULL-ACCESS-${marker}" to that temporary integration-test file. Do not use Bash and do not merely claim success.`,
      `Only after the Write tool succeeds reply exactly "FULL-ACCESS-${marker}".`,
    ].join('\n'),
    'bypassPermissions',
  )
  await waitForEvent(
    session,
    credentials,
    (event) => event.t === 'text' && String(event.text).includes(`FULL-ACCESS-${marker}`),
    'bypass tool completion',
  )
  assert(
    (await readFile(fullAccessProbe, 'utf8')).trim() === `FULL-ACCESS-${marker}`,
    'full-access Write did not create the probe',
  )
  const raw = await getRawSession(session.id, credentials)
  const state = raw.agentState ? decryptRecord(raw.agentState, session.encryptionKey, 'dataKey') as { requests?: object } : {}
  assert(!state.requests || Object.keys(state.requests).length === 0, 'bypass session unexpectedly requested approval')
}

async function spawn(
  socket: Socket,
  credentials: Credentials,
  machineId: string,
  machineKey: string,
  permissionMode: 'default' | 'bypassPermissions',
): Promise<SessionHandle> {
  const result = await rpc(socket, `${machineId}:spawn-happy-session`, {
    type: 'spawn-in-directory',
    directory,
    approvedNewDirectoryCreation: true,
    agent: 'claude',
    permissionMode,
    effortLevel: 'low',
  }, new Uint8Array(Buffer.from(machineKey, 'base64')))
  const value = result as { type?: string; sessionId?: string; errorMessage?: string }
  assert(value.type === 'success' && value.sessionId, value.errorMessage || 'spawn failed')
  return waitForSession(value.sessionId, credentials)
}

async function waitForSession(id: string, credentials: Credentials): Promise<SessionHandle> {
  const secret = secretOf(credentials)
  return poll(`session ${id}`, async () => {
    const raw = await getRawSession(id, credentials).catch(() => null)
    if (!raw?.dataEncryptionKey) return null
    const key = unwrapDataKey(raw.dataEncryptionKey, secret)
    return key ? { id, tag: '', seq: raw.seq, encryptionKey: key, encryptionVariant: 'dataKey' as const } : null
  })
}

async function waitForApproval(session: SessionHandle, credentials: Credentials): Promise<{ id: string }> {
  return poll('permission request', async () => {
    const raw = await getRawSession(session.id, credentials)
    if (!raw.agentState) return null
    const state = decryptRecord(raw.agentState, session.encryptionKey, 'dataKey') as { requests?: Record<string, unknown> } | null
    const id = state?.requests && Object.keys(state.requests)[0]
    return id ? { id } : null
  }, 180_000)
}

async function waitForEvent(
  session: SessionHandle,
  credentials: Credentials,
  predicate: (event: Record<string, unknown>) => boolean,
  label: string,
): Promise<Record<string, unknown>> {
  return poll(label, async () => {
    const messages = await api.fetchMessages(credentials, session, 0)
    for (const message of messages) {
      const outer = message.content as { role?: string; content?: { ev?: Record<string, unknown> } }
      const event = outer.content?.ev
      if (event && predicate(event)) return event
    }
    return null
  }, 240_000)
}

async function send(
  session: SessionHandle,
  credentials: Credentials,
  text: string,
  permissionMode: string,
): Promise<void> {
  await api.postMessages(credentials, session, [{
    role: 'user',
    content: { type: 'text', text },
    meta: { sentFrom: 'android-smoke', permissionMode },
  }])
}

async function getRawSession(id: string, credentials: Credentials): Promise<RawSession> {
  const response = await request('/v2/sessions?limit=200', credentials) as { sessions?: RawSession[] }
  const session = response.sessions?.find((item) => item.id === id)
  if (!session) throw new Error(`session not found: ${id}`)
  return session
}

async function request(path: string, credentials: Credentials): Promise<unknown> {
  const response = await fetch(`${relay}${path}`, {
    headers: { Authorization: `Bearer ${credentials.token}`, 'X-Happy-Client': clientId },
  })
  const text = await response.text()
  if (!response.ok) throw new Error(`HTTP ${response.status} ${path}: ${text.slice(0, 200)}`)
  return JSON.parse(text)
}

async function rpc(socket: Socket, method: string, params: unknown, key: Uint8Array): Promise<unknown> {
  const response = await withTimeout(new Promise<{ ok?: boolean; error?: string; result?: string }>((resolve) => {
    socket.emit('rpc-call', { method, params: encryptRecord(params, key, 'dataKey') }, resolve)
  }), 45_000, `RPC ${method}`)
  if (!response.ok || !response.result) throw new Error(response.error || `RPC rejected: ${method}`)
  const result = decryptRecord(response.result, key, 'dataKey')
  if (result == null) throw new Error(`RPC result could not be decrypted: ${method}`)
  return result
}

function createUserSocket(credentials: Credentials): Socket {
  return io(relay.replace(/^http/, 'ws'), {
    path: '/v1/updates',
    transports: ['websocket'],
    auth: { token: credentials.token, clientType: 'user-scoped', happyClient: clientId, appState: 'active' },
    autoConnect: false,
  })
}

function connect(socket: Socket): Promise<void> {
  return withTimeout(new Promise((resolve, reject) => {
    socket.once('connect', resolve)
    socket.once('connect_error', reject)
    socket.connect()
  }), 20_000, 'user socket connection')
}

async function poll<T>(label: string, read: () => Promise<T | null>, timeoutMs = 120_000): Promise<T> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const value = await read()
    if (value != null) return value
    await new Promise((resolve) => setTimeout(resolve, 1_000))
  }
  throw new Error(`Timed out waiting for ${label}`)
}

function withTimeout<T>(promise: Promise<T>, timeoutMs: number, label: string): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timed out waiting for ${label}`)), timeoutMs)
    promise.then(
      (value) => { clearTimeout(timer); resolve(value) },
      (error) => { clearTimeout(timer); reject(error) },
    )
  })
}

function required(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`${name} is required`)
  return value
}

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message)
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error)
  process.exitCode = 1
})
