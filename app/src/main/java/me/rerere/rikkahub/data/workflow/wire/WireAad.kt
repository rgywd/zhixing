package me.rerere.rikkahub.data.workflow.wire

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object WireAad {
    fun encode(header: WireEnvelopeHeader): ByteArray {
        header.validate()
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeLengthPrefixed(header.id)
                output.writeLengthPrefixed(header.accountId)
                output.writeLengthPrefixed(header.senderDeviceId)
                output.writeLengthPrefixed(header.targetId)
                output.writeLengthPrefixed(header.streamId)
                output.writeLong(header.seq)
                output.writeLong(header.createdAt)
                output.writeLong(header.expiresAt ?: -1L)
                output.writeLengthPrefixed(header.keyId)
            }
            bytes.toByteArray()
        }
    }

    private fun DataOutputStream.writeLengthPrefixed(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= 0xffff) { "AAD string exceeds U16 length" }
        writeShort(encoded.size)
        write(encoded)
    }

    private val MAGIC = "ZXW1".toByteArray(Charsets.UTF_8)
}
