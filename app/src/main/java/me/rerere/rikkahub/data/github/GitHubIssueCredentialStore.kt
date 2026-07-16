package me.rerere.rikkahub.data.github

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

interface GitHubIssueTokenProvider {
    fun getToken(): String?
}

class GitHubIssueCredentialStore(context: Context) : GitHubIssueTokenProvider {
    private val credentialFile = File(context.noBackupFilesDir, CREDENTIAL_FILE)

    fun saveToken(token: String) {
        val normalized = token.trim()
        if (normalized.isBlank()) {
            clear()
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val payload = listOf(cipher.iv, encrypted)
            .joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
        val temporaryFile = File(credentialFile.parentFile, "${credentialFile.name}.tmp")
        temporaryFile.writeText(payload)
        check(!credentialFile.exists() || credentialFile.delete()) { "Unable to replace GitHub credential" }
        check(temporaryFile.renameTo(credentialFile)) { "Unable to save GitHub credential" }
    }

    override fun getToken(): String? = runCatching {
        if (!credentialFile.isFile) return null
        val (encodedIv, encodedCiphertext) = credentialFile.readText().split(SEPARATOR, limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
        }
        cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
            .toString(Charsets.UTF_8)
            .trim()
            .takeIf { it.isNotBlank() }
    }.getOrNull()

    fun hasToken(): Boolean = getToken() != null

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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zhixing_github_issue_token"
        const val CREDENTIAL_FILE = "github_issue_credential"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val SEPARATOR = ":"
    }
}
