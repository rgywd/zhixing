package me.rerere.rikkahub.ui.pages.workflow.happy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HappyCredentialsStore(
    context: Context,
    private val json: Json,
) {
    private val credentialFile = File(context.noBackupFilesDir, CREDENTIAL_FILE)

    fun save(credentials: HappyCredentials) {
        val plaintext = json.encodeToString(StoredCredentials.from(credentials))
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(plaintext)
        val payload = listOf(cipher.iv, encrypted)
            .joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
        val temporaryFile = File(credentialFile.parentFile, "${credentialFile.name}.tmp")

        temporaryFile.writeText(payload)
        check(!credentialFile.exists() || credentialFile.delete()) {
            "Unable to replace Happy credentials"
        }
        check(temporaryFile.renameTo(credentialFile)) {
            "Unable to save Happy credentials"
        }
    }

    fun load(): HappyCredentials? = runCatching {
        if (!credentialFile.isFile) return null
        val parts = credentialFile.readText().split(SEPARATOR, limit = 2)
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(
                    TAG_LENGTH_BITS,
                    Base64.decode(parts[0], Base64.NO_WRAP),
                ),
            )
        }
        val plaintext = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
            .toString(Charsets.UTF_8)
        json.decodeFromString<StoredCredentials>(plaintext).toCredentials()
    }.getOrNull()

    fun clear() {
        credentialFile.delete()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    @Serializable
    private data class StoredCredentials(
        val token: String,
        val secret: String,
        val serverUrl: String = HappyProtocol.SERVER_URL,
    ) {
        fun toCredentials() = HappyCredentials(token = token, secret = secret, serverUrl = serverUrl)

        companion object {
            fun from(credentials: HappyCredentials) = StoredCredentials(
                token = credentials.token,
                secret = credentials.secret,
                serverUrl = credentials.serverUrl,
            )
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zhixing_happy_credentials"
        const val CREDENTIAL_FILE = "happy_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val SEPARATOR = ":"
    }
}
