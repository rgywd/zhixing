package me.rerere.rikkahub.data.work

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import me.rerere.rikkahub.service.PhoneWorkTrackingService

class PhoneWorkCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val credentialFile = File(context.noBackupFilesDir, CREDENTIAL_FILE)
    private val mutableConnection = MutableStateFlow(loadConnection())
    val connection: StateFlow<PhoneWorkConnection> = mutableConnection

    fun save(baseUrl: String, token: String) {
        val normalizedUrl = normalizeBaseUrl(baseUrl)
        val normalizedToken = token.trim()
        require(normalizedToken.isNotEmpty()) { "Bearer Token 不能为空" }

        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val encrypted = cipher.doFinal(normalizedToken.toByteArray(Charsets.UTF_8))
        val payload = listOf(cipher.iv, encrypted)
            .joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
        val temporaryFile = File(credentialFile.parentFile, "${credentialFile.name}.tmp")
        temporaryFile.writeText(payload)
        check(!credentialFile.exists() || credentialFile.delete()) { "无法替换 Work 凭据" }
        check(temporaryFile.renameTo(credentialFile)) { "无法保存 Work 凭据" }
        preferences.edit().putString(KEY_BASE_URL, normalizedUrl).apply()
        mutableConnection.value = PhoneWorkConnection(normalizedUrl, configured = true)
        if (me.rerere.rikkahub.BuildConfig.WORK_APP) PhoneWorkTrackingService.start(appContext)
    }

    fun clear() {
        credentialFile.delete()
        preferences.edit().remove(KEY_BASE_URL).apply()
        mutableConnection.value = PhoneWorkConnection("", configured = false)
        PhoneWorkTrackingService.stop(appContext)
    }

    fun token(): String? = runCatching {
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

    private fun loadConnection(): PhoneWorkConnection {
        val baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty()
        return PhoneWorkConnection(baseUrl, configured = baseUrl.isNotBlank() && token() != null)
    }

    private fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawPath.orEmpty().isBlank()) {
            "请输入不带子路径的 HTTPS 地址"
        }
        return normalized
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
        const val KEY_ALIAS = "zhixing_phone_work_token"
        const val CREDENTIAL_FILE = "phone_work_credential"
        const val PREFERENCES = "phone_work_connection"
        const val KEY_BASE_URL = "base_url"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val SEPARATOR = ":"
    }
}
