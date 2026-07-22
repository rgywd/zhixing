package me.rerere.rikkahub.data.quota

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.work.PhoneWorkApiClient
import java.io.File

data class QuotaRepositoryState(
    val envelope: QuotaEnvelope? = null,
    val refreshing: Boolean = false,
    val fromDeviceCache: Boolean = false,
    val errorMessage: String? = null,
)

class QuotaRepository(
    context: Context,
    private val api: PhoneWorkApiClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile = File(context.filesDir, "life-overview/quota-cache.json")
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow(QuotaRepositoryState())
    val state = mutableState.asStateFlow()

    suspend fun refresh() = refreshMutex.withLock {
        if (mutableState.value.envelope == null) {
            readCache()?.let { cached ->
                mutableState.value = QuotaRepositoryState(
                    envelope = cached,
                    refreshing = true,
                    fromDeviceCache = true,
                )
            }
        }
        mutableState.value = mutableState.value.copy(refreshing = true, errorMessage = null)
        runCatching { api.quotas().requireValid() }
            .onSuccess { envelope ->
                writeCache(envelope)
                mutableState.value = QuotaRepositoryState(
                    envelope = envelope,
                    refreshing = false,
                    fromDeviceCache = false,
                )
            }
            .onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    refreshing = false,
                    errorMessage = error.message ?: "套餐余量同步失败",
                )
            }
    }

    private suspend fun readCache(): QuotaEnvelope? = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.isFile) return@runCatching null
            json.decodeFromString<QuotaEnvelope>(cacheFile.readText()).requireValid()
        }.getOrNull()
    }

    private suspend fun writeCache(envelope: QuotaEnvelope) = withContext(Dispatchers.IO) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            temporary.writeText(json.encodeToString(envelope))
            if (!temporary.renameTo(cacheFile)) {
                cacheFile.writeText(temporary.readText())
                temporary.delete()
            }
        }
    }
}
