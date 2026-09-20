package me.rerere.rikkahub.data.work

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PhoneWorkRepoKey(
    val runnerId: String,
    val repoId: String,
)

@Serializable
data class PhoneWorkRepoPreferences(
    val pinned: List<PhoneWorkRepoKey> = emptyList(),
    val recent: List<PhoneWorkRepoKey> = emptyList(),
)

fun PhoneWorkRepoPreferences.isPinned(repo: PhoneWorkRepo): Boolean = repo.key() in pinned

fun PhoneWorkRepoPreferences.markRecent(key: PhoneWorkRepoKey): PhoneWorkRepoPreferences = copy(
    recent = (listOf(key) + recent.filterNot { it == key }).take(MAX_RECENT_WORK_REPOS),
)

fun PhoneWorkRepoPreferences.togglePinned(key: PhoneWorkRepoKey): PhoneWorkRepoPreferences = copy(
    pinned = if (key in pinned) {
        pinned.filterNot { it == key }
    } else {
        listOf(key) + pinned
    },
)

fun chooseDefaultWorkRepo(
    repos: List<PhoneWorkRepo>,
    preferences: PhoneWorkRepoPreferences,
): PhoneWorkRepo? {
    val availableByKey = repos.asSequence()
        .filter { it.available }
        .associateBy(PhoneWorkRepo::key)
    return (preferences.recent + preferences.pinned).firstNotNullOfOrNull(availableByKey::get)
}

class PhoneWorkRepoPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<PhoneWorkRepoPreferences> = mutableState.asStateFlow()

    fun markSelected(repo: PhoneWorkRepo) {
        update { it.markRecent(repo.key()) }
    }

    fun togglePinned(repo: PhoneWorkRepo) {
        update { it.togglePinned(repo.key()) }
    }

    fun importPreferences(value: PhoneWorkRepoPreferences) {
        update { value.copy(pinned = value.pinned.distinct(), recent = value.recent.distinct().take(MAX_RECENT_WORK_REPOS)) }
    }

    @Synchronized
    private fun update(transform: (PhoneWorkRepoPreferences) -> PhoneWorkRepoPreferences) {
        val updated = transform(mutableState.value)
        if (updated == mutableState.value) return
        mutableState.value = updated
        preferences.edit().putString(KEY_STATE, json.encodeToString(updated)).apply()
    }

    private fun load(): PhoneWorkRepoPreferences = preferences.getString(KEY_STATE, null)
        ?.let { runCatching { json.decodeFromString<PhoneWorkRepoPreferences>(it) }.getOrNull() }
        ?: PhoneWorkRepoPreferences()

    private companion object {
        const val PREFERENCES = "phone_work_repo_preferences"
        const val KEY_STATE = "state"
    }
}

fun PhoneWorkRepo.key() = PhoneWorkRepoKey(runnerId = runnerId, repoId = id)

private const val MAX_RECENT_WORK_REPOS = 5
