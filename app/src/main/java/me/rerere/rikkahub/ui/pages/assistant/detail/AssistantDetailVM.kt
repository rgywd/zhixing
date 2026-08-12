package me.rerere.rikkahub.ui.pages.assistant.detail

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantUserPromptSource
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.MemoryDocument
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.repository.MemoryDocumentRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.AssistantUserPromptConflictException
import me.rerere.workspace.AssistantUserPromptDocument
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryDocumentRepository: MemoryDocumentRepository,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    private val _userPromptDocument = MutableStateFlow<AssistantUserPromptDocument?>(null)
    val userPromptDocument = _userPromptDocument.asStateFlow()

    private val _userPromptError = MutableStateFlow<String?>(null)
    val userPromptError = _userPromptError.asStateFlow()

    private val _userPromptBusy = MutableStateFlow(false)
    val userPromptBusy = _userPromptBusy.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            assistant.collectLatest { current ->
                if (current.userPromptSource != AssistantUserPromptSource.KNOWLEDGE_VAULT) {
                    _userPromptDocument.value = null
                    return@collectLatest
                }
                val workspaceId = current.workspaceId?.toString()
                if (workspaceId == null) {
                    _userPromptDocument.value = null
                    return@collectLatest
                }
                _userPromptBusy.value = true
                runCatching {
                    workspaceRepository.readAssistantUserPrompt(workspaceId, current.id.toString())
                }.onSuccess {
                    _userPromptDocument.value = it
                    _userPromptError.value = null
                }.onFailure { error ->
                    _userPromptDocument.value = null
                    _userPromptError.value = error.message ?: error::class.java.simpleName
                }
                _userPromptBusy.value = false
            }
        }
    }

    val memoryDocuments = assistant
        .flatMapLatest { currentAssistant ->
            val scopeId = if (currentAssistant.useGlobalMemory) {
                MemoryDocumentRepository.GLOBAL_SCOPE_ID
            } else {
                assistantId.toString()
            }
            flow {
                memoryDocumentRepository.listDocuments(scopeId)
                emitAll(memoryDocumentRepository.observeDocuments(scopeId))
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    private val _memoryDocumentError = MutableStateFlow<String?>(null)
    val memoryDocumentError = _memoryDocumentError.asStateFlow()

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings = settings.copy(
                    assistantTags = tags
                )
            )
            update(
                assistant.value.copy(
                    tags = tagIds.toList()
                )
            )
            Log.d(TAG, "updateTags: ${tagIds.joinToString(",")}")
            cleanupUnusedTags()
        }
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch {
            val settings = settings.value
            val validTagIds = settings.assistantTags.map { it.id }.toSet()

            // 清理 assistant 中的无效 tag id
            val cleanedAssistants = settings.assistants.map { assistant ->
                val validTags = assistant.tags.filter { tagId ->
                    validTagIds.contains(tagId)
                }
                if (validTags.size != assistant.tags.size) {
                    assistant.copy(tags = validTags)
                } else {
                    assistant
                }
            }

            // 获取清理后的 assistant 中使用的 tag id
            val usedTagIds = cleanedAssistants.flatMap { it.tags }.toSet()

            // 清理未使用的 tags
            val cleanedTags = settings.assistantTags.filter { tag ->
                usedTagIds.contains(tag.id)
            }

            // 检查是否需要更新
            val needUpdateAssistants = cleanedAssistants != settings.assistants
            val needUpdateTags = cleanedTags.size != settings.assistantTags.size

            if (needUpdateAssistants || needUpdateTags) {
                settingsStore.update(
                    settings = settings.copy(
                        assistants = cleanedAssistants,
                        assistantTags = cleanedTags
                    )
                )
            }
        }
    }

    fun update(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings = settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            checkAvatarDelete(old = it, new = assistant) // 删除旧头像
                            checkBackgroundDelete(old = it, new = assistant) // 删除旧背景
                            assistant
                        } else {
                            it
                        }
                    })
            )
        }
    }

    fun setUserPromptSource(source: AssistantUserPromptSource) {
        viewModelScope.launch {
            val current = assistant.value
            if (source == AssistantUserPromptSource.APP) {
                _userPromptDocument.value = null
                _userPromptError.value = null
                update(current.copy(userPromptSource = source))
                return@launch
            }

            val workspaceId = current.workspaceId?.toString()
            if (workspaceId == null) {
                _userPromptError.value = "请先为助手绑定工作区并初始化知识库"
                return@launch
            }
            _userPromptBusy.value = true
            runCatching {
                workspaceRepository.ensureAssistantUserPrompt(
                    id = workspaceId,
                    assistantId = current.id.toString(),
                    fallbackContent = current.systemPrompt,
                )
            }.onSuccess { document ->
                _userPromptDocument.value = document
                _userPromptError.value = null
                update(current.copy(userPromptSource = source))
            }.onFailure { error ->
                if (error is AssistantUserPromptConflictException) {
                    _userPromptDocument.value = error.current
                }
                _userPromptError.value = error.message ?: error::class.java.simpleName
            }
            _userPromptBusy.value = false
        }
    }

    fun saveVaultUserPrompt(content: String, expectedRevision: String?) {
        viewModelScope.launch {
            val current = assistant.value
            val workspaceId = current.workspaceId?.toString()
            if (workspaceId == null) {
                _userPromptError.value = "助手未绑定工作区"
                return@launch
            }
            _userPromptBusy.value = true
            runCatching {
                workspaceRepository.writeAssistantUserPrompt(
                    id = workspaceId,
                    assistantId = current.id.toString(),
                    content = content,
                    expectedRevision = expectedRevision,
                )
            }.onSuccess { document ->
                _userPromptDocument.value = document
                _userPromptError.value = null
            }.onFailure { error ->
                if (error is AssistantUserPromptConflictException) {
                    _userPromptDocument.value = error.current
                }
                _userPromptError.value = error.message ?: error::class.java.simpleName
            }
            _userPromptBusy.value = false
        }
    }

    fun acceptLocationTravelPrivacyConsent() {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(locationTravelPrivacyConsent = true)
            }
        }
    }

    fun saveMemoryDocument(document: MemoryDocument) {
        viewModelScope.launch {
            runCatching {
                val scopeId = if (assistant.value.useGlobalMemory) {
                    MemoryDocumentRepository.GLOBAL_SCOPE_ID
                } else {
                    assistantId.toString()
                }
                memoryDocumentRepository.writeFromUserEditor(
                    contextScopeId = scopeId,
                    rawPath = document.path,
                    expectedVersion = document.version,
                    name = document.name,
                    description = document.description,
                    aliases = document.aliases,
                    content = document.content,
                )
            }.onSuccess {
                _memoryDocumentError.value = null
            }.onFailure { error ->
                _memoryDocumentError.value = error.message ?: error::class.java.simpleName
            }
        }
    }

    fun deleteMemoryDocument(document: MemoryDocument) {
        viewModelScope.launch {
            runCatching {
                val scopeId = if (assistant.value.useGlobalMemory) {
                    MemoryDocumentRepository.GLOBAL_SCOPE_ID
                } else {
                    assistantId.toString()
                }
                memoryDocumentRepository.delete(scopeId, document.path, document.version)
            }.onSuccess {
                _memoryDocumentError.value = null
            }.onFailure { error ->
                _memoryDocumentError.value = error.message ?: error::class.java.simpleName
            }
        }
    }

    fun clearMemoryDocumentError() {
        _memoryDocumentError.value = null
    }

    fun checkAvatarDelete(old: Assistant, new: Assistant) {
        if (old.avatar is Avatar.Image && old.avatar != new.avatar) {
            filesManager.deleteChatFiles(listOf(old.avatar.url.toUri()))
        }
    }

    fun checkBackgroundDelete(old: Assistant, new: Assistant) {
        val oldBackground = old.background
        val newBackground = new.background

        if (oldBackground != null && oldBackground != newBackground) {
            try {
                val oldUri = oldBackground.toUri()
                if (oldUri.scheme == "content" || oldUri.scheme == "file") {
                    filesManager.deleteChatFiles(listOf(oldUri))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete background file: $oldBackground", e)
            }
        }
    }
}
