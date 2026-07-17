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
import me.rerere.rikkahub.data.workflow.WorkSpawnOutcome
import me.rerere.rikkahub.data.workflow.WorkSpawnRequest

class WorkNewTaskVM(
    private val repository: WorkRepository,
) : ViewModel() {
    var presets by mutableStateOf<List<RepoPreset>>(emptyList())
        private set
    var machines by mutableStateOf<List<WorkMachine>>(emptyList())
        private set

    var selectedPresetId by mutableStateOf<String?>(null)
        private set
    var machineId by mutableStateOf("")
        private set
    var path by mutableStateOf("")
        private set
    var agent by mutableStateOf(WorkAgent.CODEX)
        private set
    var model by mutableStateOf("")
        private set
    var reasoningEffort by mutableStateOf<String?>(null)
        private set
    var fullAccess by mutableStateOf(false)
        private set
    var prompt by mutableStateOf("")
        private set

    var isSubmitting by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pendingDirectory by mutableStateOf<String?>(null)
        private set
    var createdSessionId by mutableStateOf<String?>(null)
        private set

    private var prefillPresetId: String? = null
    private var prefillApplied = false

    init {
        viewModelScope.launch {
            repository.observePresets().collect { list ->
                presets = list
                applyPendingPrefill()
            }
        }
        viewModelScope.launch { repository.observeMachines().collect { machines = it } }
        viewModelScope.launch {
            repository.ensureRealtime()
            runCatching { repository.refreshSnapshot() }
        }
    }

    /** 路由参数只消费一次；presetId 优先于 machineId/path 预填 */
    fun prefill(presetId: String?, initialMachineId: String?, initialPath: String?) {
        if (prefillApplied) return
        prefillApplied = true
        if (presetId != null) {
            prefillPresetId = presetId
            applyPendingPrefill()
        } else {
            initialMachineId?.let { machineId = it }
            initialPath?.let { path = it }
        }
    }

    private fun applyPendingPrefill() {
        val target = prefillPresetId ?: return
        presets.firstOrNull { it.id == target }?.let { preset ->
            prefillPresetId = null
            applyPreset(preset)
        }
    }

    fun applyPreset(preset: RepoPreset) {
        selectedPresetId = preset.id
        machineId = preset.machineId
        path = preset.path
        agent = if (preset.agent == WorkAgent.OTHER) WorkAgent.CODEX else preset.agent
        model = preset.model.orEmpty()
        reasoningEffort = preset.reasoningEffort
        fullAccess = preset.fullAccess
    }

    fun clearPreset() {
        selectedPresetId = null
    }

    fun updateMachine(id: String) {
        machineId = id
        if (path.isBlank()) {
            path = machines.firstOrNull { it.id == id }?.homeDir.orEmpty()
        }
    }

    fun updatePath(value: String) {
        path = value
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

    fun updatePrompt(value: String) {
        prompt = value
    }

    val canSubmit: Boolean
        get() = machineId.isNotBlank() && path.isNotBlank() && prompt.isNotBlank() && !isSubmitting

    fun submit(approvedNewDirectoryCreation: Boolean = false) {
        if (!canSubmit && !approvedNewDirectoryCreation) return
        val preset = presets.firstOrNull { it.id == selectedPresetId }
        val request = WorkSpawnRequest(
            machineId = machineId,
            directory = path,
            agent = agent,
            prompt = prompt,
            model = model.takeIf(String::isNotBlank),
            reasoningEffort = reasoningEffort,
            fullAccess = fullAccess,
            disallowedTools = preset?.disallowedTools.orEmpty(),
            approvedNewDirectoryCreation = approvedNewDirectoryCreation,
        )
        viewModelScope.launch {
            isSubmitting = true
            error = null
            try {
                when (val outcome = repository.spawnSession(request)) {
                    is WorkSpawnOutcome.Success -> createdSessionId = outcome.sessionId
                    is WorkSpawnOutcome.NeedsDirectoryApproval -> pendingDirectory = outcome.directory
                    is WorkSpawnOutcome.Error -> error = outcome.message
                }
            } catch (throwable: Throwable) {
                error = throwable.toWorkflowMessage()
            } finally {
                isSubmitting = false
            }
        }
    }

    fun approveDirectoryCreation() {
        if (pendingDirectory == null) return
        pendingDirectory = null
        submit(approvedNewDirectoryCreation = true)
    }

    fun dismissDirectoryApproval() {
        pendingDirectory = null
    }

    fun consumeCreatedSession() {
        createdSessionId = null
    }
}
