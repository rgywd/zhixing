package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.uuid.Uuid

enum class WorkspaceVariableScope {
    TEMPORARY,
    USER,
}

data class WorkspaceVariableDeclaration(
    val name: String,
    val value: String,
    val scope: WorkspaceVariableScope,
)

data class WorkspaceVariableParseResult(
    val declarations: List<WorkspaceVariableDeclaration>,
    val safeText: String,
)

private val VARIABLE_DECLARATION_REGEX =
    Regex("""^(\$\$|\$)([A-Za-z_][A-Za-z0-9_]*)=(.*)$""")

/**
 * Recognizes a consecutive declaration block at the very beginning of a message.
 *
 * `$NAME=value` is temporary for the current conversation. `$$NAME=value` is a
 * persistent user variable. An empty value removes the variable in that scope.
 * Everything after the first non-declaration line remains ordinary user text.
 */
fun parseWorkspaceVariableDeclarations(text: String): WorkspaceVariableParseResult {
    val lines = text.split('\n')
    val declarations = mutableListOf<WorkspaceVariableDeclaration>()
    var consumedLines = 0

    for (line in lines) {
        val match = VARIABLE_DECLARATION_REGEX.matchEntire(line.removeSuffix("\r")) ?: break
        declarations += WorkspaceVariableDeclaration(
            name = match.groupValues[2],
            value = match.groupValues[3],
            scope = if (match.groupValues[1] == "$$") {
                WorkspaceVariableScope.USER
            } else {
                WorkspaceVariableScope.TEMPORARY
            },
        )
        consumedLines += 1
    }

    if (declarations.isEmpty()) {
        return WorkspaceVariableParseResult(emptyList(), text)
    }

    val remainingText = lines
        .drop(consumedLines)
        .dropWhile(String::isBlank)
        .joinToString("\n")
    val safeNotice = buildString {
        appendLine("[安全变量已处理]")
        declarations.forEach { declaration ->
            val scopeLabel = when (declaration.scope) {
                WorkspaceVariableScope.TEMPORARY -> "临时变量"
                WorkspaceVariableScope.USER -> "用户变量"
            }
            val action = if (declaration.value.isEmpty()) "已删除" else "已设置"
            appendLine("- $scopeLabel `${declaration.name}` $action")
        }
        append("变量值未写入聊天记录，也不会发送给模型；workspace shell 仅按变量名引用。")
        if (remainingText.isNotBlank()) {
            appendLine()
            appendLine()
            append(remainingText)
        }
    }
    return WorkspaceVariableParseResult(declarations, safeNotice)
}

fun redactWorkspaceVariableValues(
    text: String,
    values: Collection<String>,
): String = values
    .asSequence()
    .filter(String::isNotEmpty)
    .distinct()
    .sortedByDescending(String::length)
    .fold(text) { redacted, value ->
        redacted.replace(value, REDACTED_VALUE)
    }

internal fun mergeWorkspaceVariableEnvironment(
    userVariables: Map<String, String>,
    temporaryVariables: Map<String, String>,
): Map<String, String> = buildMap {
    putAll(userVariables)
    putAll(temporaryVariables)
}

/**
 * Keeps conversation variables only in process memory and user variables in an
 * Android Keystore encrypted, no-backup file.
 */
class WorkspaceVariableStore(context: Context) {
    private val credentialFile = File(context.noBackupFilesDir, CREDENTIAL_FILE)
    private val temporaryVariables =
        ConcurrentHashMap<Uuid, ConcurrentHashMap<String, String>>()
    private val userVariablesLock = Any()

    @Volatile
    private var userVariables: Map<String, String> = loadUserVariables()

    suspend fun apply(
        conversationId: Uuid,
        declarations: List<WorkspaceVariableDeclaration>,
    ) = withContext(Dispatchers.IO) {
        require(declarations.all { declaration ->
            declaration.name.matches(VARIABLE_NAME_REGEX) &&
                declaration.value.length <= MAX_VALUE_LENGTH &&
                !declaration.value.contains('\u0000')
        }) {
            "Invalid workspace variable declaration"
        }

        val userUpdates = declarations.filter {
            it.scope == WorkspaceVariableScope.USER
        }
        if (userUpdates.isNotEmpty()) {
            synchronized(userVariablesLock) {
                val updated = userVariables.toMutableMap()
                userUpdates.forEach { declaration ->
                    if (declaration.value.isEmpty()) {
                        updated.remove(declaration.name)
                    } else {
                        updated[declaration.name] = declaration.value
                    }
                }
                require(updated.size <= MAX_VARIABLE_COUNT) {
                    "Too many user workspace variables"
                }
                persistUserVariables(updated)
                userVariables = updated.toMap()
            }
        }

        val temporaryUpdates = declarations.filter {
            it.scope == WorkspaceVariableScope.TEMPORARY
        }
        if (temporaryUpdates.isNotEmpty()) {
            val updated = temporaryVariables[conversationId].orEmpty().toMutableMap()
            temporaryUpdates.forEach { declaration ->
                if (declaration.value.isEmpty()) {
                    updated.remove(declaration.name)
                } else {
                    updated[declaration.name] = declaration.value
                }
            }
            require(updated.size <= MAX_VARIABLE_COUNT) {
                "Too many temporary workspace variables"
            }
            if (updated.isEmpty()) {
                temporaryVariables.remove(conversationId)
            } else {
                temporaryVariables[conversationId] = ConcurrentHashMap(updated)
            }
        }
    }

    fun environment(conversationId: Uuid): Map<String, String> =
        mergeWorkspaceVariableEnvironment(
            userVariables = userVariables,
            temporaryVariables = temporaryVariables[conversationId].orEmpty(),
        )

    fun clearTemporary(conversationId: Uuid) {
        temporaryVariables.remove(conversationId)
    }

    private fun loadUserVariables(): Map<String, String> = runCatching {
        if (!credentialFile.isFile) return emptyMap()
        val (encodedIv, encodedCiphertext) = credentialFile.readText().split(SEPARATOR, limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(
                    TAG_LENGTH_BITS,
                    Base64.decode(encodedIv, Base64.NO_WRAP),
                ),
            )
        }
        decodeVariables(
            cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
                .toString(Charsets.UTF_8)
        )
    }.getOrDefault(emptyMap())

    private fun persistUserVariables(variables: Map<String, String>) {
        if (variables.isEmpty()) {
            check(!credentialFile.exists() || credentialFile.delete()) {
                "Unable to delete workspace variables"
            }
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(encodeVariables(variables).toByteArray(Charsets.UTF_8))
        val payload = listOf(cipher.iv, encrypted)
            .joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
        val temporaryFile = File(credentialFile.parentFile, "${credentialFile.name}.tmp")
        temporaryFile.writeText(payload)
        check(!credentialFile.exists() || credentialFile.delete()) {
            "Unable to replace workspace variables"
        }
        check(temporaryFile.renameTo(credentialFile)) {
            "Unable to save workspace variables"
        }
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
        const val KEY_ALIAS = "zhixing_workspace_user_variables"
        const val CREDENTIAL_FILE = "workspace_user_variables"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val SEPARATOR = ":"
        const val ENTRY_SEPARATOR = "="
        const val MAX_VARIABLE_COUNT = 128
        const val MAX_VALUE_LENGTH = 16 * 1024
        val VARIABLE_NAME_REGEX = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        fun encodeVariables(variables: Map<String, String>): String =
            variables.toSortedMap().entries.joinToString("\n") { (name, value) ->
                "$name$ENTRY_SEPARATOR${
                    Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                }"
            }

        fun decodeVariables(payload: String): Map<String, String> =
            payload.lineSequence()
                .filter(String::isNotBlank)
                .associate { line ->
                    val name = line.substringBefore(ENTRY_SEPARATOR)
                    require(name.matches(VARIABLE_NAME_REGEX)) {
                        "Invalid workspace variable name"
                    }
                    val encodedValue = line.substringAfter(ENTRY_SEPARATOR)
                    name to Base64.decode(encodedValue, Base64.NO_WRAP)
                        .toString(Charsets.UTF_8)
                }
    }
}

private const val REDACTED_VALUE = "[REDACTED_SECRET]"
