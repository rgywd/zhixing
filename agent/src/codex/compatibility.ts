import type { CodexInitializeInfo } from './protocol.js'

export const CODEX_SCHEMA_BASELINE = Object.freeze({
  codexVersion: '0.144.x',
  experimentalApi: false,
  schemaHash: 'e75404842a291fc0473a34abc0d3cd9b036182210f3c0e37709515dbab247ba0',
  operations: [
    'thread.list',
    'thread.read',
    'thread.resume',
    'thread.fork',
    'thread.archive',
    'thread.unarchive',
    'thread.delete',
    'turn.start',
    'turn.steer',
    'turn.interrupt',
  ],
})

export interface CodexCompatibility {
  detectedVersion: string | null
  catalogReadable: boolean
  runtimeWritable: boolean
  reason: string | null
  schemaHash: string | null
  operations: string[]
}

export function analyzeCodexCompatibility(
  info: CodexInitializeInfo,
  schemaHash: string | null,
): CodexCompatibility {
  const version = extractCodexVersion(info.userAgent)
  const versionReviewed = version?.major === 0 && version.minor === 144
  const schemaReviewed = schemaHash === CODEX_SCHEMA_BASELINE.schemaHash
  const runtimeWritable = versionReviewed && schemaReviewed
  return {
    detectedVersion: version?.raw ?? null,
    catalogReadable: true,
    runtimeWritable,
    reason: runtimeWritable
      ? null
      : !version
        ? '无法识别 Codex 版本，目录保持只读'
        : !versionReviewed
          ? `Codex ${version.raw} 未通过 0.144.x 写入适配验证，目录保持只读`
          : schemaHash === null
            ? '无法生成 Codex schema hash，目录保持只读'
            : 'Codex schema 与 0.144.x 基线不一致，目录保持只读',
    schemaHash,
    operations: runtimeWritable ? [...CODEX_SCHEMA_BASELINE.operations] : ['thread.list', 'thread.read'],
  }
}

interface ParsedVersion {
  major: number
  minor: number
  patch: number
  raw: string
}

function extractCodexVersion(userAgent: string): ParsedVersion | null {
  const match = userAgent.match(/(?:Codex Desktop|codex(?:-cli)?)[\s/]+(\d+)\.(\d+)\.(\d+)/i)
  if (!match) return null
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
    raw: `${match[1]}.${match[2]}.${match[3]}`,
  }
}
