package me.rerere.rikkahub.ui.pages.workflow.happy

import java.util.Base64

object HappySecretKeyCodec {
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
        require(decoded.size == KEY_SIZE) {
            "Happy recovery key must decode to $KEY_SIZE bytes"
        }
        return decoded
    }

    fun encodeBase64Url(secret: ByteArray): String {
        require(secret.size == KEY_SIZE)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    }

    fun formatForBackup(secret: ByteArray): String {
        require(secret.size == KEY_SIZE)
        return encodeBase32(secret).chunked(5).joinToString("-")
    }

    private fun decodeBase32(value: String): ByteArray {
        val normalized = value.uppercase()
            .replace('0', 'O')
            .replace('1', 'I')
            .replace('8', 'B')
            .replace('9', 'G')
            .filter { it in ALPHABET }
        require(normalized.isNotEmpty()) { "Happy recovery key contains no valid characters" }

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

    private fun encodeBase32(bytes: ByteArray): String {
        val result = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                result.append(ALPHABET[(buffer shr bits) and 0x1f])
            }
        }
        if (bits > 0) {
            result.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        }
        return result.toString()
    }

    private fun padBase64Url(value: String): String = when (value.length % 4) {
        2 -> "$value=="
        3 -> "$value="
        else -> value
    }
}
