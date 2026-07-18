package me.rerere.rikkahub.data.workflow.wire

import java.util.Base64

object WireRecoveryKeyCodec {
    private const val KEY_SIZE = 32
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(value: String): ByteArray {
        val trimmed = value.trim()
        val decoded = if (trimmed.any { it == '-' || it.isWhitespace() } || trimmed.length > 50) {
            decodeBase32(trimmed)
        } else {
            runCatching { Base64.getUrlDecoder().decode(padBase64Url(trimmed)) }
                .getOrElse { decodeBase32(trimmed) }
        }
        require(decoded.size == KEY_SIZE) { "恢复密钥必须是 32 bytes" }
        return decoded
    }

    fun encode(secret: ByteArray): String {
        require(secret.size == KEY_SIZE)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    }

    private fun decodeBase32(value: String): ByteArray {
        val normalized = value.uppercase()
            .replace('0', 'O')
            .replace('1', 'I')
            .replace('8', 'B')
            .replace('9', 'G')
            .filter { it in ALPHABET }
        require(normalized.isNotEmpty()) { "恢复密钥格式不正确" }
        val output = ArrayList<Byte>()
        var buffer = 0
        var bits = 0
        for (character in normalized) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(character)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output += ((buffer shr bits) and 0xff).toByte()
            }
        }
        return output.toByteArray()
    }

    private fun padBase64Url(value: String): String = when (value.length % 4) {
        2 -> "$value=="
        3 -> "$value="
        else -> value
    }
}
