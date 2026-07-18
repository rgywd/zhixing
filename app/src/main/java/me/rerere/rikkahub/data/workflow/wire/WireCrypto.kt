package me.rerere.rikkahub.data.workflow.wire

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class WireCrypto(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun encryptBytes(
        plaintext: ByteArray,
        key: ByteArray,
        header: WireEnvelopeHeader,
        nonce: ByteArray = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes),
    ): String {
        require(key.size == DATA_KEY_SIZE) { "key must be $DATA_KEY_SIZE bytes" }
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
        val ciphertext = Cipher.getInstance(AES_TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
            updateAAD(WireAad.encode(header))
            doFinal(plaintext)
        }
        val bundle = byteArrayOf(CIPHER_VERSION) + nonce + ciphertext
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bundle)
    }

    fun decryptBytes(
        cipherBundle: String,
        key: ByteArray,
        header: WireEnvelopeHeader,
    ): ByteArray {
        require(key.size == DATA_KEY_SIZE) { "key must be $DATA_KEY_SIZE bytes" }
        return try {
            val bundle = Base64.getUrlDecoder().decode(cipherBundle)
            require(bundle.size >= MIN_BUNDLE_SIZE && bundle[0] == CIPHER_VERSION) {
                "unsupported or truncated cipher bundle"
            }
            val nonce = bundle.copyOfRange(1, 1 + NONCE_SIZE)
            val ciphertext = bundle.copyOfRange(1 + NONCE_SIZE, bundle.size)
            Cipher.getInstance(AES_TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
                updateAAD(WireAad.encode(header))
                doFinal(ciphertext)
            }
        } catch (error: Exception) {
            throw WireProtocolException(
                WireErrorCode.DECRYPTION_FAILED,
                "Wire v1 authentication failed",
                error,
            )
        }
    }

    companion object {
        const val CIPHER_VERSION: Byte = 1
        const val DATA_KEY_SIZE = 32
        const val NONCE_SIZE = 12
        private const val TAG_LENGTH_BITS = 128
        private const val TAG_SIZE = TAG_LENGTH_BITS / 8
        private const val MIN_BUNDLE_SIZE = 1 + NONCE_SIZE + TAG_SIZE
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
