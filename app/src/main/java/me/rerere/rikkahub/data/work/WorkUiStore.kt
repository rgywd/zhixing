package me.rerere.rikkahub.data.work

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

@Serializable
enum class WorkAppMode {
    CHAT,
    WORK,
}

@Serializable
data class WorkRepositoryPreferences(
    val model: String? = null,
    /** Kept for schema-1 files. New writes use [effortByModel]. */
    val effort: String? = null,
    val effortByModel: Map<String, String> = emptyMap(),
    val permission: String? = null,
    val fastMode: Boolean = false,
) {
    fun effortFor(modelId: String?): String? = modelId?.let(effortByModel::get) ?: effort

    fun withEffort(modelId: String?, value: String?): WorkRepositoryPreferences {
        if (modelId == null || value == null) return copy(effort = value)
        return copy(
            effort = null,
            effortByModel = effortByModel + (modelId to value),
        )
    }
}

@Serializable
data class WorkRepositoryConfig(
    val id: String = Uuid.random().toString(),
    val displayName: String,
    val path: String,
    val machineId: String? = null,
    val legacyProjectId: String? = null,
    val currentThreadId: String? = null,
    val draft: String = "",
    val preferences: WorkRepositoryPreferences = WorkRepositoryPreferences(),
)

@Serializable
data class WorkUiState(
    val schema: Int = 2,
    val mode: WorkAppMode = WorkAppMode.CHAT,
    val activeRepositoryId: String? = null,
    val repositories: List<WorkRepositoryConfig> = emptyList(),
) {
    val activeRepository: WorkRepositoryConfig?
        get() = repositories.firstOrNull { it.id == activeRepositoryId }

    fun normalized(): WorkUiState {
        val uniqueRepositories = repositories
            .filter { it.displayName.isNotBlank() && it.path.isNotBlank() }
            .distinctBy { it.id }
        val selectedId = activeRepositoryId?.takeIf { id -> uniqueRepositories.any { it.id == id } }
            ?: uniqueRepositories.firstOrNull()?.id
        return copy(activeRepositoryId = selectedId, repositories = uniqueRepositories)
    }
}

interface WorkUiStore {
    val state: StateFlow<WorkUiState>

    suspend fun setMode(mode: WorkAppMode)
    suspend fun selectRepository(repositoryId: String?)
    suspend fun upsertRepository(repository: WorkRepositoryConfig)
    suspend fun removeRepository(repositoryId: String)
    suspend fun clearCurrentThread(repositoryId: String)
    suspend fun updateRepositorySession(
        repositoryId: String,
        currentThreadId: String? = null,
        draft: String? = null,
        preferences: WorkRepositoryPreferences? = null,
    )
}

class FileWorkUiStore(
    context: Context,
    private val json: Json,
) : WorkUiStore {
    private val atomicFile = AtomicFile(File(context.filesDir, FILE_NAME))
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(readState())

    override val state: StateFlow<WorkUiState> = mutableState.asStateFlow()

    override suspend fun setMode(mode: WorkAppMode) = update { it.copy(mode = mode) }

    override suspend fun selectRepository(repositoryId: String?) = update { current ->
        current.copy(
            activeRepositoryId = repositoryId?.takeIf { id -> current.repositories.any { it.id == id } },
        )
    }

    override suspend fun upsertRepository(repository: WorkRepositoryConfig) = update { current ->
        val clean = repository.copy(
            displayName = repository.displayName.trim(),
            path = repository.path.trim(),
            machineId = repository.machineId?.trim()?.takeIf(String::isNotEmpty),
            legacyProjectId = repository.legacyProjectId?.trim()?.takeIf(String::isNotEmpty),
        )
        require(clean.displayName.isNotBlank()) { "Repository name cannot be blank" }
        require(clean.path.isNotBlank()) { "Repository path cannot be blank" }
        val repositories = current.repositories.toMutableList()
        val existingIndex = repositories.indexOfFirst { it.id == clean.id }
        if (existingIndex >= 0) repositories[existingIndex] = clean else repositories += clean
        current.copy(
            activeRepositoryId = current.activeRepositoryId ?: clean.id,
            repositories = repositories,
        )
    }

    override suspend fun removeRepository(repositoryId: String) = update { current ->
        val repositories = current.repositories.filterNot { it.id == repositoryId }
        current.copy(
            activeRepositoryId = current.activeRepositoryId
                .takeUnless { it == repositoryId }
                ?: repositories.firstOrNull()?.id,
            repositories = repositories,
        )
    }

    override suspend fun clearCurrentThread(repositoryId: String) = update { current ->
        current.copy(
            repositories = current.repositories.map { repository ->
                if (repository.id == repositoryId) repository.copy(currentThreadId = null, draft = "") else repository
            },
        )
    }

    override suspend fun updateRepositorySession(
        repositoryId: String,
        currentThreadId: String?,
        draft: String?,
        preferences: WorkRepositoryPreferences?,
    ) = update { current ->
        current.copy(
            repositories = current.repositories.map { repository ->
                if (repository.id != repositoryId) repository
                else repository.copy(
                    currentThreadId = currentThreadId ?: repository.currentThreadId,
                    draft = draft ?: repository.draft,
                    preferences = preferences ?: repository.preferences,
                )
            },
        )
    }

    private suspend fun update(transform: (WorkUiState) -> WorkUiState) {
        mutex.withLock {
            val next = transform(mutableState.value).normalized()
            withContext(Dispatchers.IO) { writeState(next) }
            mutableState.value = next
        }
    }

    private fun readState(): WorkUiState = runCatching {
        if (!atomicFile.baseFile.isFile) return@runCatching WorkUiState()
        atomicFile.openRead().bufferedReader().use { reader ->
            json.decodeFromString<WorkUiState>(reader.readText()).normalized()
        }
    }.getOrDefault(WorkUiState())

    private fun writeState(state: WorkUiState) {
        val stream = atomicFile.startWrite()
        try {
            OutputStreamWriter(stream).apply {
                write(json.encodeToString(state))
                flush()
            }
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val FILE_NAME = "work-ui.json"
    }
}
