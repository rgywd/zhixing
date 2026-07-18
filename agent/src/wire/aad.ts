import type { WireEnvelopeHeader } from './types.js'
import { validateEnvelopeHeader } from './types.js'

const MAGIC = Buffer.from('ZXW1', 'utf8')

export function buildWireAad(value: WireEnvelopeHeader): Uint8Array {
  const header = validateEnvelopeHeader(value)
  return Buffer.concat([
    MAGIC,
    lengthPrefixed(header.id),
    lengthPrefixed(header.accountId),
    lengthPrefixed(header.senderDeviceId),
    lengthPrefixed(header.targetId),
    lengthPrefixed(header.streamId),
    unsignedLong(header.seq),
    signedLong(header.createdAt),
    signedLong(header.expiresAt ?? -1),
    lengthPrefixed(header.keyId),
  ])
}

function lengthPrefixed(value: string): Buffer {
  const encoded = Buffer.from(value, 'utf8')
  if (encoded.length > 0xffff) throw new RangeError('AAD string exceeds U16 length')
  const length = Buffer.allocUnsafe(2)
  length.writeUInt16BE(encoded.length)
  return Buffer.concat([length, encoded])
}

function unsignedLong(value: number): Buffer {
  const output = Buffer.allocUnsafe(8)
  output.writeBigUInt64BE(BigInt(value))
  return output
}

function signedLong(value: number): Buffer {
  const output = Buffer.allocUnsafe(8)
  output.writeBigInt64BE(BigInt(value))
  return output
}
