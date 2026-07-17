/**
 * 中继 Socket.IO 机器 scope 客户端。
 *
 * 协议要点（happy CLI apiMachine.ts / RpcHandlerManager.ts）：
 * - path=/v1/updates，仅 websocket，auth {token, clientType:'machine-scoped', machineId, happyClient}
 * - 服务端下发 `rpc-request` {method, params}，ack 回调返回 base64(加密(JSON)) 字符串
 * - 注册方法：emit `rpc-register` {method: '<machineId>:<名字>'}，重连后需重发
 * - 心跳：`machine-alive` {machineId, time} 每 20 秒
 */
import { io, type Socket } from 'socket.io-client'
import { decryptRecord, encryptRecord } from './crypto.js'
import type { Credentials, MachineIdentity } from './types.js'

const KEEPALIVE_INTERVAL_MS = 20_000

export type RpcHandler = (params: unknown) => Promise<unknown>

export interface MachineSocketOptions {
  serverUrl: string
  clientId: string
  credentials: Credentials
  identity: MachineIdentity
  onConnectionChange?: (connected: boolean) => void
}

export class MachineSocket {
  private readonly socket: Socket
  private readonly machineKey: Uint8Array
  private readonly handlers = new Map<string, RpcHandler>()
  private keepalive: NodeJS.Timeout | null = null

  constructor(private readonly options: MachineSocketOptions) {
    this.machineKey = new Uint8Array(Buffer.from(options.identity.machineKey, 'base64'))
    this.socket = io(options.serverUrl.replace(/^http/, 'ws'), {
      path: '/v1/updates',
      transports: ['websocket'],
      auth: {
        token: options.credentials.token,
        clientType: 'machine-scoped',
        machineId: options.identity.machineId,
        happyClient: options.clientId,
      },
      reconnection: true,
      reconnectionDelay: 1_000,
      reconnectionDelayMax: 10_000,
      autoConnect: false,
    })

    this.socket.on('connect', () => {
      this.registerAll()
      this.startKeepalive()
      options.onConnectionChange?.(true)
    })
    this.socket.on('disconnect', () => {
      this.stopKeepalive()
      options.onConnectionChange?.(false)
    })
    this.socket.on(
      'rpc-request',
      (payload: { method?: string; params?: string }, ack?: (result: string) => void) => {
        void this.dispatch(payload, ack)
      },
    )
  }

  connect(): void {
    this.socket.connect()
  }

  close(): void {
    this.stopKeepalive()
    this.socket.disconnect()
  }

  /** 注册机器 RPC 方法（自动加 machineId 前缀） */
  register(method: string, handler: RpcHandler): void {
    const prefixed = `${this.options.identity.machineId}:${method}`
    this.handlers.set(prefixed, handler)
    if (this.socket.connected) {
      this.socket.emit('rpc-register', { method: prefixed })
    }
  }

  private registerAll(): void {
    for (const method of this.handlers.keys()) {
      this.socket.emit('rpc-register', { method })
    }
  }

  private async dispatch(
    payload: { method?: string; params?: string },
    ack?: (result: string) => void,
  ): Promise<void> {
    if (!ack || !payload.method) return
    const handler = this.handlers.get(payload.method)
    if (!handler) {
      ack(this.encryptResult({ error: `Unknown method: ${payload.method}` }))
      return
    }
    try {
      const params =
        typeof payload.params === 'string'
          ? decryptRecord(payload.params, this.machineKey, 'dataKey')
          : payload.params
      const result = await handler(params)
      ack(this.encryptResult(result ?? {}))
    } catch (error) {
      ack(this.encryptResult({ error: error instanceof Error ? error.message : String(error) }))
    }
  }

  private encryptResult(value: unknown): string {
    return encryptRecord(value, this.machineKey, 'dataKey')
  }

  private startKeepalive(): void {
    this.stopKeepalive()
    this.keepalive = setInterval(() => {
      this.socket.emit('machine-alive', {
        machineId: this.options.identity.machineId,
        time: Date.now(),
      })
    }, KEEPALIVE_INTERVAL_MS)
  }

  private stopKeepalive(): void {
    if (this.keepalive) {
      clearInterval(this.keepalive)
      this.keepalive = null
    }
  }
}
