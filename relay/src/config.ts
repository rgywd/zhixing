import { resolve } from 'node:path'
import { z } from 'zod'

const envSchema = z.object({
  ZHIXING_RELAY_HOST: z.string().default('127.0.0.1'),
  ZHIXING_RELAY_PORT: z.coerce.number().int().min(1).max(65_535).default(3100),
  ZHIXING_RELAY_DATA_DIR: z.string().default('./data'),
  ZHIXING_RELAY_TOKEN_TTL_MS: z.coerce.number().int().min(60_000).default(30 * 24 * 60 * 60 * 1_000),
  ZHIXING_RELAY_CHALLENGE_TTL_MS: z.coerce.number().int().min(10_000).max(300_000).default(60_000),
  ZHIXING_RELAY_LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
})

export interface RelayConfig {
  host: string
  port: number
  dataDir: string
  databasePath: string
  tokenTtlMs: number
  challengeTtlMs: number
  logLevel: string
}

export function loadConfig(environment: NodeJS.ProcessEnv = process.env): RelayConfig {
  const parsed = envSchema.parse(environment)
  const dataDir = resolve(parsed.ZHIXING_RELAY_DATA_DIR)
  return {
    host: parsed.ZHIXING_RELAY_HOST,
    port: parsed.ZHIXING_RELAY_PORT,
    dataDir,
    databasePath: resolve(dataDir, 'relay.sqlite3'),
    tokenTtlMs: parsed.ZHIXING_RELAY_TOKEN_TTL_MS,
    challengeTtlMs: parsed.ZHIXING_RELAY_CHALLENGE_TTL_MS,
    logLevel: parsed.ZHIXING_RELAY_LOG_LEVEL,
  }
}
