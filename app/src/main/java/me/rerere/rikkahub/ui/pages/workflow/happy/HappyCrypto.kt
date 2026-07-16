package me.rerere.rikkahub.ui.pages.workflow.happy

import com.iwebpp.crypto.TweetNaclFast
import java.security.SecureRandom

data class HappyAuthChallenge(
    val challenge: ByteArray,
    val signature: ByteArray,
    val publicKey: ByteArray,
)

class HappyCrypto(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun createAuthChallenge(
        accountSecret: ByteArray,
        challenge: ByteArray = ByteArray(CHALLENGE_SIZE).also(secureRandom::nextBytes),
    ): HappyAuthChallenge {
        require(accountSecret.size == ACCOUNT_SECRET_SIZE)
        require(challenge.size == CHALLENGE_SIZE)

        val keyPair = TweetNaclFast.Signature.keyPair_fromSeed(accountSecret)
        val signer = TweetNaclFast.Signature(keyPair.publicKey, keyPair.secretKey)
        return HappyAuthChallenge(
            challenge = challenge.copyOf(),
            signature = requireNotNull(signer.detached(challenge)),
            publicKey = keyPair.publicKey.copyOf(),
        )
    }

    companion object {
        const val ACCOUNT_SECRET_SIZE = 32
        const val CHALLENGE_SIZE = 32
    }
}
