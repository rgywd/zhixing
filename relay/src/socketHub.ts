import type { IncomingMessage, Server } from 'node:http'
import { WebSocket, WebSocketServer } from 'ws'
import { bearerToken } from './auth.js'
import type { RelayMetrics } from './metrics.js'
import type { RelayRepository } from './repository.js'

interface ConnectionIdentity {
  accountId: string
  deviceId: string
}

export class RelaySocketHub {
  private readonly server = new WebSocketServer({ noServer: true, maxPayload: 1_024 })
  private readonly connections = new Map<string, Set<WebSocket>>()
  private readonly heartbeat: NodeJS.Timeout

  constructor(
    httpServer: Server,
    private readonly repository: RelayRepository,
    private readonly metrics: RelayMetrics,
  ) {
    httpServer.on('upgrade', (request, socket, head) => this.upgrade(request, socket, head))
    this.heartbeat = setInterval(() => this.pingConnections(), 20_000)
    this.heartbeat.unref()
  }

  notifyOutbox(accountId: string, deviceIds: string[]): void {
    for (const deviceId of deviceIds) {
      this.send(accountId, deviceId, { type: 'outbox.available' })
    }
  }

  isOnline(accountId: string, deviceId: string): boolean {
    return (this.connections.get(keyOf(accountId, deviceId))?.size ?? 0) > 0
  }

  closeDevice(accountId: string, deviceId: string): void {
    for (const socket of this.connections.get(keyOf(accountId, deviceId)) ?? []) {
      socket.close(4003, 'device revoked')
    }
  }

  close(): void {
    clearInterval(this.heartbeat)
    for (const group of this.connections.values()) {
      for (const socket of group) socket.close(1001, 'server shutdown')
    }
    this.server.close()
  }

  private upgrade(request: IncomingMessage, socket: import('node:stream').Duplex, head: Buffer): void {
    const url = new URL(request.url ?? '/', 'http://relay.local')
    if (url.pathname !== '/v1/socket') return
    try {
      const token = bearerToken(request.headers.authorization)
      const auth = this.repository.authenticate(token)
      const header = request.headers['x-zhixing-device-id']
      const deviceId = Array.isArray(header) ? header[0] : header
      if (!deviceId) throw new Error('missing device header')
      if (auth.deviceId !== deviceId) throw new Error('device token mismatch')
      this.repository.requireActiveDevice(auth.accountId, deviceId)
      const identity = { accountId: auth.accountId, deviceId }
      this.server.handleUpgrade(request, socket, head, (webSocket) => this.connected(webSocket, identity))
    } catch {
      this.metrics.increment('websocket_auth_failed')
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n')
      socket.destroy()
    }
  }

  private connected(socket: WebSocket, identity: ConnectionIdentity): void {
    const key = keyOf(identity.accountId, identity.deviceId)
    const group = this.connections.get(key) ?? new Set<WebSocket>()
    group.add(socket)
    this.connections.set(key, group)
    this.repository.touchDevice(identity.accountId, identity.deviceId)
    this.metrics.increment('websocket_connected')
    socket.send(JSON.stringify({ type: 'ready', heartbeatMs: 20_000 }))
    socket.on('pong', () => this.repository.touchDevice(identity.accountId, identity.deviceId))
    socket.on('message', (data, binary) => {
      const size = Array.isArray(data)
        ? data.reduce((total, part) => total + part.byteLength, 0)
        : data.byteLength
      if (binary || size > 1_024) return socket.close(1009, 'message too large')
      try {
        const message = JSON.parse(data.toString()) as { type?: string }
        if (message.type === 'ping') {
          this.repository.touchDevice(identity.accountId, identity.deviceId)
          socket.send(JSON.stringify({ type: 'pong', at: Date.now() }))
        }
      } catch {
        socket.close(1003, 'invalid json')
      }
    })
    socket.on('close', () => {
      group.delete(socket)
      if (group.size === 0) this.connections.delete(key)
      this.metrics.increment('websocket_disconnected')
    })
  }

  private send(accountId: string, deviceId: string, message: unknown): void {
    const encoded = JSON.stringify(message)
    for (const socket of this.connections.get(keyOf(accountId, deviceId)) ?? []) {
      if (socket.readyState === WebSocket.OPEN) socket.send(encoded)
    }
  }

  private pingConnections(): void {
    for (const group of this.connections.values()) {
      for (const socket of group) if (socket.readyState === WebSocket.OPEN) socket.ping()
    }
  }
}

function keyOf(accountId: string, deviceId: string): string {
  return `${accountId}\u0000${deviceId}`
}
