package me.rerere.rikkahub.data.work

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
data class WorkConnectionCredentials(
    val connectionId: String,
    val displayName: String,
    val appServerUrl: String,
    val appServerToken: String,
    val supervisorUrl: String? = null,
    val supervisorToken: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class WorkConnections(
    val activeConnectionId: String? = null,
    val connections: List<WorkConnectionCredentials> = emptyList(),
)

interface WorkConnectionStore {
    fun load(): WorkConnections
    fun save(connection: WorkConnectionCredentials, makeActive: Boolean = true)
    fun setActive(connectionId: String?)
    fun remove(connectionId: String)
    fun clear()
}

/** Stores all App Server and supervisor bearer tokens in an Android Keystore encrypted file. */
class EncryptedWorkConnectionStore(
    context: Context,
    private val json: Json,
) : WorkConnectionStore {
    private val credentialFile = File(context.noBackupFilesDir, CREDENTIAL_FILE)

    @Synchronized
    override fun load(): WorkConnections = loadPayload()?.toModel() ?: WorkConnections()

    @Synchronized
    override fun save(connection: WorkConnectionCredentials, makeActive: Boolean) {
        val current = loadPayload() ?: StoredWorkConnections()
        val connections = current.connections
            .filterNot { it.connectionId == connection.connectionId } + connection
        write(
            current.copy(
                activeConnectionId = if (makeActive) connection.connectionId else current.activeConnectionId,
                connections = connections,
            )
        )
    }

    @Synchronized
    override fun setActive(connectionId: String?) {
        val current = loadPayload() ?: StoredWorkConnections()
        require(connectionId == null || current.connections.any { it.connectionId == connectionId }) {
            "Unknown Work connection"
        }
        write(current.copy(activeConnectionId = connectionId))
    }

    @Synchronized
    override fun remove(connectionId: String) {
        val current = loadPayload() ?: return
        val remaining = current.connections.filterNot { it.connectionId == connectionId }
        write(
            current.copy(
                activeConnectionId = current.activeConnectionId.takeIf { id -> remaining.any { it.connectionId == id } },
                connections = remaining,
            )
        )
    }

    @Synchronized
    override fun clear() {
        credentialFile.delete()
    }

    private fun loadPayload(): StoredWorkConnections? = runCatching {
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
        json.decodeFromString<StoredWorkConnections>(
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        ).takeIf { it.schema == SCHEMA }
    }.getOrNull()

    private fun write(payload: StoredWorkConnections) {
        val plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val encoded = listOf(cipher.iv, cipher.doFinal(plaintext))
            .joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
        val temporary = File(credentialFile.parentFile, "${credentialFile.name}.tmp")
        temporary.writeText(encoded)
        check(!credentialFile.exists() || credentialFile.delete()) { "无法替换 Work 连接凭据" }
        check(temporary.renameTo(credentialFile)) { "无法保存 Work 连接凭据" }
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
        const val SCHEMA = 1
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zhixing_work_connections_v1"
        const val CREDENTIAL_FILE = "work_connections_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val SEPARATOR = ":"
    }
}

@Serializable
private data class StoredWorkConnections(
    val schema: Int = 1,
    val activeConnectionId: String? = null,
    val connections: List<WorkConnectionCredentials> = emptyList(),
) {
    fun toModel() = WorkConnections(
        activeConnectionId = activeConnectionId.takeIf { id -> connections.any { it.connectionId == id } },
        connections = connections,
    )
}
