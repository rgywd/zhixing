package me.rerere.rikkahub.ui.pages.workflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.workflow.RepoPreset
import me.rerere.rikkahub.data.workflow.WorkAgent
import me.rerere.rikkahub.data.workflow.WorkMachine
import me.rerere.rikkahub.data.workflow.WorkReasoningEffort
import me.rerere.rikkahub.data.workflow.WorkRepository
import me.rerere.rikkahub.data.workflow.newRepoPresetId

class WorkPresetEditVM(
    private val repository: WorkRepository,
) : ViewModel() {
    var machines by mutableStateOf<List<WorkMachine>>(emptyList())
        private set

    var name by mutableStateOf("")
        private set
    var machineId by mutableStateOf("")
        private set
    var path by mutableStateOf("")
        private set
    var defaultBranch by mutableStateOf("")
        private set
    var agent by mutableStateOf(WorkAgent.CODEX)
        private set
    var model by mutableStateOf("")
        private set
    var reasoningEffort by mutableStateOf<String?>(null)
        private set
    var fullAccess by mutableStateOf(false)
        private set

    /** 每行一条硬性限制（disallowedTools 规则），仅 Claude Code 侧远端强制 */
    var disallowedToolsText by mutableStateOf("")
        private set

    var isSaving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var finished by mutableStateOf(false)
        private set
    var isExisting by mutableStateOf(false)
        private set

    private var editingId: String? = null
    private var createdAt: Long? = null
    private var prefillApplied = false

    init {
        viewModelScope.launch { repository.observeMachines().collect { machines = it } }
    }

    fun prefill(presetId: String?, initialMachineId: String?, initialPath: String?) {
        if (prefillApplied) return
        prefillApplied = true
        if (presetId == null) {
            initialMachineId?.let { machineId = it }
            initialPath?.let {
                path = it
                if (name.isBlank()) name = projectName(it)
            }
            return
        }
        viewModelScope.launch {
            repository.getPreset(presetId)?.let { preset ->
                editingId = preset.id
                createdAt = preset.createdAt
                isExisting = true
                name = preset.name
                machineId = preset.machineId
                path = preset.path
                defaultBranch = preset.defaultBranch.orEmpty()
                agent = preset.agent
                model = preset.model.orEmpty()
                reasoningEffort = preset.reasoningEffort
                fullAccess = preset.fullAccess
                disallowedToolsText = preset.disallowedTools.joinToString("\n")
            }
        }
    }

    fun updateName(value: String) {
        name = value
    }

    fun updateMachine(id: String) {
        machineId = id
    }

    fun updatePath(value: String) {
        path = value
        if (name.isBlank()) name = projectName(value)
    }

    fun updateDefaultBranch(value: String) {
        defaultBranch = value
    }

    fun updateAgent(value: WorkAgent) {
        agent = value
        if (reasoningEffort != null && reasoningEffort !in WorkReasoningEffort.levelsFor(value)) {
            reasoningEffort = null
        }
    }

    fun updateModel(value: String) {
        model = value
    }

    fun updateReasoningEffort(value: String?) {
        reasoningEffort = value
    }

    fun updateFullAccess(value: Boolean) {
        fullAccess = value
    }

    fun updateDisallowedTools(value: String) {
        disallowedToolsText = value
    }

    val canSave: Boolean
        get() = name.isNotBlank() && machineId.isNotBlank() && path.isNotBlank() && !isSaving

    fun save() {
        if (!canSave) return
        viewModelScope.launch {
            isSaving = true
            error = null
            try {
                val now = System.currentTimeMillis()
                repository.savePreset(
                    RepoPreset(
                        id = editingId ?: newRepoPresetId(),
                        name = name.trim(),
                        machineId = machineId,
                        path = path.trim(),
                        defaultBranch = defaultBranch.trim().takeIf(String::isNotBlank),
                        agent = agent,
                        model = model.trim().takeIf(String::isNotBlank),
                        reasoningEffort = reasoningEffort,
                        fullAccess = fullAccess,
                        disallowedTools = disallowedToolsText.lines()
                            .map(String::trim)
                            .filter(String::isNotBlank),
                        createdAt = createdAt ?: now,
                        updatedAt = now,
                    )
                )
                finished = true
            } catch (throwable: Throwable) {
                error = throwable.toWorkflowMessage()
            } finally {
                isSaving = false
            }
        }
    }

    fun delete() {
        val id = editingId ?: return
        viewModelScope.launch {
            isSaving = true
            try {
                repository.deletePreset(id)
                finished = true
            } catch (throwable: Throwable) {
                error = throwable.toWorkflowMessage()
            } finally {
                isSaving = false
            }
        }
    }
}
