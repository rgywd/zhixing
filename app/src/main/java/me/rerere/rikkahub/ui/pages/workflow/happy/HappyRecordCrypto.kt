package me.rerere.rikkahub.ui.pages.workflow.happy

import com.iwebpp.crypto.TweetNaclFast
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

enum class HappyEncryptionVariant {
    LEGACY,
    DATA_KEY,
}

class HappyRecordCrypto(
    private val json: Json,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun unwrapDataKey(
        encoded: String,
        accountSecret: ByteArray,
    ): ByteArray? = runCatching {
        require(accountSecret.size == HappyCrypto.ACCOUNT_SECRET_SIZE)
        val bundle = Base64.getDecoder().decode(encoded)
        if (bundle.size < WRAPPED_KEY_MIN_SIZE || bundle[0] != WRAPPED_KEY_VERSION) {
            return null
        }

        val ephemeralPublicKey = bundle.copyOfRange(1, 33)
        val nonce = bundle.copyOfRange(33, 57)
        val ciphertext = bundle.copyOfRange(57, bundle.size)
        val contentSecretKey = deriveContentBoxSecretKey(accountSecret)
        TweetNaclFast.Box(ephemeralPublicKey, contentSecretKey).open(ciphertext, nonce)
    }.getOrNull()

    fun decryptJson(
        encoded: String,
        key: ByteArray,
        variant: HappyEncryptionVariant = HappyEncryptionVariant.DATA_KEY,
    ): JsonObject? = decryptElement(encoded, key, variant) as? JsonObject

    fun decryptElement(
        encoded: String,
        key: ByteArray,
        variant: HappyEncryptionVariant = HappyEncryptionVariant.DATA_KEY,
    ): JsonElement? = runCatching {
        val plaintext = when (variant) {
            HappyEncryptionVariant.DATA_KEY -> decryptAesGcm(Base64.getDecoder().decode(encoded), key)
            HappyEncryptionVariant.LEGACY -> decryptSecretBox(Base64.getDecoder().decode(encoded), key)
        } ?: return null
        json.parseToJsonElement(plaintext.toString(Charsets.UTF_8))
    }.getOrNull()

    fun encryptElement(
        value: JsonElement,
        key: ByteArray,
        variant: HappyEncryptionVariant = HappyEncryptionVariant.DATA_KEY,
    ): String {
        val plaintext = value.toString().toByteArray(Charsets.UTF_8)
        val bundle = when (variant) {
            HappyEncryptionVariant.DATA_KEY -> encryptAesGcm(plaintext, key)
            HappyEncryptionVariant.LEGACY -> encryptSecretBox(plaintext, key)
        }
        return Base64.getEncoder().encodeToString(bundle)
    }

    private fun deriveContentBoxSecretKey(accountSecret: ByteArray): ByteArray {
        val root = hmacSha512(
            key = "$CONTENT_KEY_USAGE Master Seed".toByteArray(Charsets.UTF_8),
            data = accountSecret,
        )
        val child = hmacSha512(
            key = root.copyOfRange(32, 64),
            data = byteArrayOf(0) + CONTENT_KEY_PATH.toByteArray(Charsets.UTF_8),
        )
        val contentSeed = child.copyOfRange(0, 32)
        return MessageDigest.getInstance("SHA-512")
            .digest(contentSeed)
            .copyOfRange(0, 32)
    }

    private fun decryptAesGcm(bundle: ByteArray, key: ByteArray): ByteArray? {
        if (key.size != AES_KEY_SIZE || bundle.size < AES_MIN_SIZE || bundle[0] != AES_VERSION) {
            return null
        }
        val nonce = bundle.copyOfRange(1, 13)
        val ciphertextWithTag = bundle.copyOfRange(13, bundle.size)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce),
            )
            doFinal(ciphertextWithTag)
        }
    }

    private fun encryptAesGcm(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == AES_KEY_SIZE)
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
            doFinal(plaintext)
        }
        return byteArrayOf(AES_VERSION) + nonce + encrypted
    }

    private fun decryptSecretBox(bundle: ByteArray, key: ByteArray): ByteArray? {
        if (key.size != TweetNaclFast.SecretBox.keyLength || bundle.size <= TweetNaclFast.SecretBox.nonceLength) {
            return null
        }
        val nonce = bundle.copyOfRange(0, TweetNaclFast.SecretBox.nonceLength)
        val ciphertext = bundle.copyOfRange(TweetNaclFast.SecretBox.nonceLength, bundle.size)
        return TweetNaclFast.SecretBox(key).open(ciphertext, nonce)
    }

    private fun encryptSecretBox(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == TweetNaclFast.SecretBox.keyLength)
        val nonce = ByteArray(TweetNaclFast.SecretBox.nonceLength).also(secureRandom::nextBytes)
        return nonce + requireNotNull(TweetNaclFast.SecretBox(key).box(plaintext, nonce))
    }

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA512").run {
            init(SecretKeySpec(key, "HmacSHA512"))
            doFinal(data)
        }

    private companion object {
        const val CONTENT_KEY_USAGE = "Happy EnCoder"
        const val CONTENT_KEY_PATH = "content"
        const val WRAPPED_KEY_VERSION: Byte = 0
        const val WRAPPED_KEY_MIN_SIZE = 1 + 32 + 24 + 16
        const val AES_VERSION: Byte = 0
        const val AES_KEY_SIZE = 32
        const val AES_MIN_SIZE = 1 + 12 + 16
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
