package me.rerere.rikkahub.data.workflow.wire

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

@Serializable
data class WireRelayCredentials(
    val serverUrl: String,
    val accountId: String,
    val deviceId: String,
    val token: String,
    val tokenExpiresAt: Long,
    val rootSecret: String,
    val wrappedKeys: Map<String, String> = emptyMap(),
    val acknowledgements: Map<String, Long> = emptyMap(),
)

interface WireCredentialsStore {
    fun save(credentials: WireRelayCredentials)
    fun load(): WireRelayCredentials?
    fun clear()
}

class WireRelayCredentialsStore(context: Context, private val json: Json) : WireCredentialsStore {
    private val credentialFile = File(context.noBackupFilesDir, CREDENTIAL_FILE)

    @Synchronized
    override fun save(credentials: WireRelayCredentials) {
        val plaintext = json.encodeToString(credentials).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val payload = listOf(cipher.iv, cipher.doFinal(plaintext))
            .joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
        val temporary = File(credentialFile.parentFile, "${credentialFile.name}.tmp")
        temporary.writeText(payload)
        check(!credentialFile.exists() || credentialFile.delete()) { "无法替换开发环境凭据" }
        check(temporary.renameTo(credentialFile)) { "无法保存开发环境凭据" }
    }

    @Synchronized
    override fun load(): WireRelayCredentials? = runCatching {
        if (!credentialFile.isFile) return null
        val parts = credentialFile.readText().split(SEPARATOR, limit = 2)
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
        }
        json.decodeFromString<WireRelayCredentials>(
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        )
    }.getOrNull()

    @Synchronized
    override fun clear() {
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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zhixing_wire_credentials_v1"
        const val CREDENTIAL_FILE = "wire_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val SEPARATOR = ":"
    }
}
