package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.knowledge.KnowledgeSpaceService
import me.rerere.workspace.KnowledgeSpaceStatus
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.VaultGitRemoteConflictException
import me.rerere.workspace.VaultGitStatus
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceStorageArea

class WorkspaceDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val knowledgeSpaceService: KnowledgeSpaceService,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkspaceDetailState())
    val state = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(WorkspaceTerminalState())
    val terminalState = _terminalState.asStateFlow()

    private val _installProgress = MutableStateFlow<RootfsInstallProgress?>(null)
    val installProgress = _installProgress.asStateFlow()

    private val _installError = MutableStateFlow<String?>(null)
    val installError = _installError.asStateFlow()

    init {
        loadWorkspace()
        refresh()
        refreshVault()
    }

    fun selectArea(area: WorkspaceStorageArea) {
        _state.update {
            it.copy(
                area = area,
                path = "",
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        val path = state.value.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                entries = emptyList(),
                error = null,
            )
        }
        refresh()
    }

    fun openVault(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        val relativePath = entry.path
            .removePrefix("vault/")
            .takeUnless { it == "vault" }
            .orEmpty()
        _state.update {
            it.copy(
                vault = it.vault.copy(
                    path = relativePath,
                    entries = emptyList(),
                    error = null,
                )
            )
        }
        refreshVault()
    }

    fun goUpVault() {
        val path = state.value.vault.path
        if (path.isBlank()) return
        _state.update {
            it.copy(
                vault = it.vault.copy(
                    path = path.substringBeforeLast('/', missingDelimiterValue = ""),
                    entries = emptyList(),
                    error = null,
                )
            )
        }
        refreshVault()
    }

    fun refresh() {
        refreshKnowledgeStatus()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.listFiles(
                    id = id,
                    area = state.value.area,
                    path = state.value.path,
                )
            }.onSuccess { entries ->
                _state.update { it.copy(entries = entries, loading = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        entries = emptyList(),
                        loading = false,
                        error = error.message ?: "加载工作区文件失败",
                    )
                }
            }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching {
                repository.deleteFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    recursive = entry.isDirectory,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "删除失败") }
            }
        }
    }

    fun importFile(inputStream: InputStream, fileName: String) {
        viewModelScope.launch {
            runCatching {
                repository.importFile(
                    id = id,
                    area = state.value.area,
                    destinationPath = state.value.path,
                    fileName = fileName,
                    inputStream = inputStream,
                )
            }.onSuccess {
                refresh()
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导入文件失败") }
            }
        }
    }

    fun initializeKnowledgeSpace() {
        viewModelScope.launch {
            _state.update { it.copy(knowledgeBusy = true, error = null) }
            runCatching { repository.initializeKnowledgeSpace(id) }
                .onSuccess { status ->
                    _state.update { it.copy(knowledgeStatus = status, knowledgeBusy = false) }
                    refreshVault()
                    refresh()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            knowledgeBusy = false,
                            error = error.message ?: "初始化知识空间失败",
                        )
                    }
                }
        }
    }

    fun importKnowledgeDocument(inputStream: InputStream, fileName: String, mimeType: String?) {
        viewModelScope.launch {
            _state.update { it.copy(knowledgeBusy = true, error = null) }
            runCatching {
                knowledgeSpaceService.importDocument(id, fileName, mimeType, inputStream)
            }.onSuccess {
                refreshKnowledgeStatus()
                refreshVault()
                refresh()
                _state.update { it.copy(knowledgeBusy = false) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        knowledgeBusy = false,
                        error = error.message ?: "导入知识失败",
                    )
                }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, outputStream: OutputStream) {
        viewModelScope.launch {
            runCatching {
                repository.exportFile(
                    id = id,
                    area = state.value.area,
                    path = entry.path,
                    outputStream = outputStream,
                )
            }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    /**
     * 把当前区域下的文件导出到 cacheDir 的临时文件, 完成后回调 [onReady].
     * 供分享 / 图片预览 / 交给系统应用打开等复用 (它们都需要一个 FileProvider 可访问的真实 File).
     */
    fun exportToCacheFile(
        entry: WorkspaceFileEntry,
        cacheDir: File,
        area: WorkspaceStorageArea = state.value.area,
        onReady: (File) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                val dir = File(cacheDir, "workspace_share").apply { mkdirs() }
                val file = File(dir, entry.name)
                file.outputStream().use { output ->
                    repository.exportFile(
                        id = id,
                        area = area,
                        path = entry.path,
                        outputStream = output,
                    )
                }
                file
            }.onSuccess(onReady).onFailure { error ->
                _state.update { it.copy(error = error.message ?: "导出文件失败") }
            }
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        viewModelScope.launch {
            val workspace = state.value.workspace ?: return@launch
            repository.setToolApproval(workspace.id, toolName, needsApproval)
            loadWorkspace()
        }
    }

    fun installRootfs(url: String) {
        viewModelScope.launch {
            _installError.value = null
            val workspace = state.value.workspace ?: return@launch
            _installProgress.value = RootfsInstallProgress(stage = RootfsInstallStage.DOWNLOADING)
            try {
                repository.installRootfs(workspace.id, url) { progress ->
                    _installProgress.value = progress
                }
                loadWorkspace()
                refresh()
                refreshVault()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                _installError.value = error.message ?: "Rootfs 安装失败"
            } finally {
                _installProgress.value = null
            }
        }
    }

    fun dismissInstallError() {
        _installError.value = null
    }

    fun refreshVault() {
        viewModelScope.launch {
            _state.update {
                it.copy(vault = it.vault.copy(loading = true, error = null))
            }
            runCatching {
                val status = repository.knowledgeSpaceStatus(id)
                if (!status.initialized) {
                    null
                } else {
                    repository.listKnowledgeContents(id, state.value.vault.path) to
                        repository.vaultGitStatus(id)
                }
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        vault = it.vault.copy(
                            entries = result?.first.orEmpty(),
                            gitStatus = result?.second,
                            loading = false,
                            error = null,
                        )
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        vault = it.vault.copy(
                            entries = emptyList(),
                            loading = false,
                            error = error.message ?: "加载知识库失败",
                        )
                    )
                }
            }
        }
    }

    fun bindVaultGitRemote(remoteUrl: String, replaceExisting: Boolean = false) {
        val normalizedRemote = remoteUrl.trim()
        if (normalizedRemote.isBlank()) {
            _state.update {
                it.copy(vault = it.vault.copy(error = "请输入 Git 仓库地址"))
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    vault = it.vault.copy(
                        gitBusy = true,
                        error = null,
                        pendingRemoteUrl = null,
                    )
                )
            }
            runCatching {
                repository.bindVaultGitRemote(
                    id = id,
                    remoteUrl = normalizedRemote,
                    replaceExisting = replaceExisting,
                )
            }.onSuccess { result ->
                _state.update {
                    it.copy(
                        vault = it.vault.copy(
                            gitStatus = result.status,
                            gitBusy = false,
                            error = null,
                            pendingRemoteUrl = null,
                        )
                    )
                }
                refreshVault()
            }.onFailure { error ->
                if (error is VaultGitRemoteConflictException) {
                    _state.update {
                        it.copy(
                            vault = it.vault.copy(
                                gitBusy = false,
                                error = null,
                                pendingRemoteUrl = normalizedRemote,
                            )
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            vault = it.vault.copy(
                                gitBusy = false,
                                error = error.message ?: "绑定 Git 仓库失败",
                                pendingRemoteUrl = null,
                            )
                        )
                    }
                }
            }
        }
    }

    fun installVaultGit() {
        viewModelScope.launch {
            _state.update {
                it.copy(vault = it.vault.copy(gitBusy = true, error = null))
            }
            runCatching {
                repository.installVaultGit(id)
            }.onSuccess { status ->
                _state.update {
                    it.copy(
                        vault = it.vault.copy(
                            gitStatus = status,
                            gitBusy = false,
                            error = null,
                        )
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        vault = it.vault.copy(
                            gitBusy = false,
                            error = error.message ?: "安装 Git 失败",
                        )
                    )
                }
            }
        }
    }

    fun replaceVaultGitRemote() {
        val remoteUrl = state.value.vault.pendingRemoteUrl ?: return
        bindVaultGitRemote(remoteUrl, replaceExisting = true)
    }

    fun dismissVaultGitRemoteConflict() {
        _state.update {
            it.copy(vault = it.vault.copy(pendingRemoteUrl = null))
        }
    }

    fun executeTerminalCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        // 原子地完成「检查 running」与「置 running=true」, 避免两次快速提交并发启动两条命令
        val previous = _terminalState.getAndUpdate { state ->
            if (state.running) {
                state
            } else {
                state.copy(
                    running = true,
                    input = "",
                    history = state.history + WorkspaceTerminalEntry.Command(trimmed),
                )
            }
        }
        if (previous.running) return
        viewModelScope.launch {
            runCatching {
                repository.executeCommand(id, trimmed)
            }.onSuccess { result ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Result(result),
                    )
                }
            }.onFailure { error ->
                _terminalState.update {
                    it.copy(
                        running = false,
                        history = it.history + WorkspaceTerminalEntry.Error(error.message ?: "命令执行失败"),
                    )
                }
            }
        }
    }

    fun updateTerminalInput(input: String) {
        _terminalState.update { it.copy(input = input) }
    }

    fun clearTerminal() {
        _terminalState.update { it.copy(history = emptyList()) }
    }

    private fun loadWorkspace() {
        viewModelScope.launch {
            val workspace = repository.getById(id)
            _state.update { it.copy(workspace = workspace) }
        }
    }

    private fun refreshKnowledgeStatus() {
        viewModelScope.launch {
            runCatching { repository.knowledgeSpaceStatus(id) }
                .onSuccess { status -> _state.update { it.copy(knowledgeStatus = status) } }
        }
    }
}

data class WorkspaceDetailState(
    val workspace: WorkspaceEntity? = null,
    val area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val knowledgeStatus: KnowledgeSpaceStatus? = null,
    val knowledgeBusy: Boolean = false,
    val vault: KnowledgeVaultState = KnowledgeVaultState(),
)

data class KnowledgeVaultState(
    val path: String = "",
    val entries: List<WorkspaceFileEntry> = emptyList(),
    val gitStatus: VaultGitStatus? = null,
    val loading: Boolean = false,
    val gitBusy: Boolean = false,
    val error: String? = null,
    val pendingRemoteUrl: String? = null,
)

data class WorkspaceTerminalState(
    val input: String = "",
    val running: Boolean = false,
    val history: List<WorkspaceTerminalEntry> = emptyList(),
)

sealed interface WorkspaceTerminalEntry {
    data class Command(val command: String) : WorkspaceTerminalEntry
    data class Result(val result: WorkspaceCommandResult) : WorkspaceTerminalEntry
    data class Error(val message: String) : WorkspaceTerminalEntry
}
