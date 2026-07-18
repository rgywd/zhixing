package me.rerere.rikkahub.data.workflow.wire

import com.iwebpp.crypto.TweetNaclFast
import java.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object WireKeys {
    fun deriveAuthSeed(rootSecret: ByteArray): ByteArray = deriveSeed(AUTH_DOMAIN, rootSecret)

    fun deriveContentSecretKey(rootSecret: ByteArray): ByteArray = deriveSeed(CONTENT_DOMAIN, rootSecret)

    fun deriveContentPublicKey(rootSecret: ByteArray): ByteArray =
        TweetNaclFast.Box.keyPair_fromSecretKey(deriveContentSecretKey(rootSecret)).publicKey

    fun wrapDataKey(
        dataKey: ByteArray,
        recipientPublicKey: ByteArray,
        secureRandom: SecureRandom = SecureRandom(),
    ): String {
        require(dataKey.size == 32)
        require(recipientPublicKey.size == TweetNaclFast.Box.publicKeyLength)
        val ephemeralSecret = ByteArray(TweetNaclFast.Box.secretKeyLength).also(secureRandom::nextBytes)
        val ephemeral = TweetNaclFast.Box.keyPair_fromSecretKey(ephemeralSecret)
        val nonce = ByteArray(TweetNaclFast.Box.nonceLength).also(secureRandom::nextBytes)
        val ciphertext = requireNotNull(TweetNaclFast.Box(recipientPublicKey, ephemeral.secretKey).box(dataKey, nonce))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            byteArrayOf(WRAPPED_KEY_VERSION) + ephemeral.publicKey + nonce + ciphertext
        )
    }

    fun unwrapDataKey(bundleBase64Url: String, recipientSecretKey: ByteArray): ByteArray? = runCatching {
        require(recipientSecretKey.size == TweetNaclFast.Box.secretKeyLength)
        val bundle = Base64.getUrlDecoder().decode(bundleBase64Url)
        if (bundle.size < WRAPPED_KEY_MIN_SIZE || bundle[0] != WRAPPED_KEY_VERSION) return null
        val ephemeralPublicKey = bundle.copyOfRange(1, 33)
        val nonce = bundle.copyOfRange(33, 57)
        val ciphertext = bundle.copyOfRange(57, bundle.size)
        TweetNaclFast.Box(ephemeralPublicKey, recipientSecretKey).open(ciphertext, nonce)
    }.getOrNull()

    private fun deriveSeed(domain: String, rootSecret: ByteArray): ByteArray {
        require(rootSecret.size == ROOT_SECRET_SIZE)
        return Mac.getInstance("HmacSHA512").run {
            init(SecretKeySpec(domain.toByteArray(Charsets.UTF_8), "HmacSHA512"))
            doFinal(rootSecret).copyOfRange(0, 32)
        }
    }

    private const val ROOT_SECRET_SIZE = 32
    private const val AUTH_DOMAIN = "Zhixing Wire v1 auth"
    private const val CONTENT_DOMAIN = "Zhixing Wire v1 content"
    private const val WRAPPED_KEY_VERSION: Byte = 1
    private const val WRAPPED_KEY_MIN_SIZE = 1 + 32 + 24 + 16
}
