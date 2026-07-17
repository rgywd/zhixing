import type { EncryptionVariant } from './crypto.js'

export interface Credentials {
  token: string
  /** base64url 编码的 32 字节账户 secret（P1 直接导入恢复密钥，与 App 同源） */
  secret: string
}

export interface MachineIdentity {
  machineId: string
  /** base64 编码的 32 字节机器内容密钥（本机生成并持久化） */
  machineKey: string
}

export interface MachineMetadata {
  host: string
  platform: string
  happyCliVersion: string
  homeDir: string
  happyHomeDir: string
  cliAvailability?: Record<string, boolean>
  [key: string]: unknown
}

export interface SessionHandle {
  id: string
  tag: string
  seq: number
  encryptionKey: Uint8Array
  encryptionVariant: EncryptionVariant
}

export type PermissionMode = 'default' | 'bypassPermissions'

export interface SpawnParams {
  directory: string
  sessionId?: string
  machineId?: string
  approvedNewDirectoryCreation?: boolean
  agent?: string
  permissionMode?: string
  modelMode?: string
  effortLevel?: string
  environmentVariables?: Record<string, string>
  resumeCodexThreadId?: string
  /** Claude transcript id；P2 每轮用 --resume 恢复，不等同于 Happy session id */
  resumeClaudeSessionId?: string
}

export type SpawnResult =
  | { type: 'success'; sessionId: string }
  | { type: 'requestToApproveDirectoryCreation'; directory: string }
  | { type: 'error'; errorMessage: string }

/** Session Protocol v2 事件（上行子集：agent 只需要会发这些） */
export type SessionEvent =
  | { t: 'text'; text: string; thinking?: boolean }
  | { t: 'service'; kind: string; text?: string; [key: string]: unknown }
  | {
      t: 'tool-call-start'
      call: string
      name: string
      title?: string
      description?: string
      args?: unknown
    }
  | { t: 'tool-call-end'; call: string; status: 'ok' | 'error'; result?: unknown }
  | { t: 'file'; path: string; kind?: string; diff?: string }
  | { t: 'turn-start' }
  | { t: 'turn-end'; status: 'completed' | 'failed' | 'cancelled' }

export interface SessionEnvelope {
  id: string
  time: number
  role: 'user' | 'agent'
  turn?: number
  subagent?: string
  ev: SessionEvent
  usage?: unknown
}
