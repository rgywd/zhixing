package me.rerere.rikkahub.ui.pages.workflow.codex

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import androidx.core.net.toFile
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.workflow.codex.CodexCatalogRepository
import me.rerere.rikkahub.data.workflow.codex.CodexProject
import me.rerere.rikkahub.data.workflow.codex.CodexThread
import me.rerere.rikkahub.data.workflow.codex.CodexThreadDetail
import me.rerere.rikkahub.data.workflow.codex.CodexRuntimeState
import me.rerere.rikkahub.data.workflow.codex.CodexRuntimeSettingsState
import me.rerere.rikkahub.data.workflow.codex.CodexInputPayload
import me.rerere.rikkahub.data.workflow.codex.CodexModelOption
import me.rerere.rikkahub.data.workflow.codex.CodexPermissionProfile
import me.rerere.rikkahub.data.workflow.codex.CodexSkillOption
import me.rerere.rikkahub.data.workflow.codex.RuntimeCatalogPayload
import me.rerere.rikkahub.data.workflow.codex.RuntimeCommandPayload
import me.rerere.rikkahub.data.workflow.codex.curateCodexHome
import me.rerere.rikkahub.data.workflow.codex.needsAttention
import me.rerere.rikkahub.data.workflow.wire.WireRelayClient
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager

class CodexWorkflowVM(
    private val repository: CodexCatalogRepository,
    private val relayClient: WireRelayClient,
) : ViewModel() {
    var projects by mutableStateOf<List<CodexProject>>(emptyList())
        private set
    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<CodexThread>>(emptyList())
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var isConnected by mutableStateOf(relayClient.connected)
        private set
    var recoveryKey by mutableStateOf("")
        private set
    var relayUrl by mutableStateOf(DEFAULT_RELAY_URL)
        private set
    private var searchJob: Job? = null
    var isStartingTask by mutableStateOf(false)
        private set
    var newThreadTarget by mutableStateOf<Pair<String, String>?>(null)
        private set
    private var startTaskRequestId: String? = null

    val needsAttention by derivedStateOf {
        projects.flatMap(CodexProject::threads)
            .filter { !it.isSubagent && it.runtimeState.needsAttention }
            .sortedByDescending(CodexThread::recencyAt)
    }
    val homeCatalog by derivedStateOf { curateCodexHome(projects) }

    init {
        viewModelScope.launch { repository.observeProjects().collect { projects = it } }
        if (isConnected) refresh()
        viewModelScope.launch {
            while (true) {
                delay(1_500)
                if (relayClient.connected && !isRefreshing) runCatching { relayClient.sync(200) }
                startTaskRequestId?.let { requestId ->
                    projects.map(CodexProject::machineId).distinct().forEach { machineId ->
                        relayClient.consumeCommandResult(
                            machineId,
                            null,
                            "thread.start",
                            requestId,
                        )?.let { result ->
                            startTaskRequestId = null
                            isStartingTask = false
                            if (!result.ok) {
                                statusMessage = result.error ?: "新建任务失败"
                            } else {
                                val threadId = result.result?.get("threadId")?.jsonPrimitive?.contentOrNull
                                if (threadId != null) newThreadTarget = machineId to threadId
                            }
                        }
                    }
                }
            }
        }
    }

    fun project(projectId: String): CodexProject? = projects.firstOrNull { it.projectId == projectId }

    fun setProjectPinned(project: CodexProject, isPinned: Boolean) {
        viewModelScope.launch { repository.setProjectPinned(project.projectId, isPinned) }
    }

    fun setProjectHidden(project: CodexProject, isHidden: Boolean) {
        viewModelScope.launch { repository.setProjectHidden(project.projectId, isHidden) }
    }

    fun setThreadPinned(thread: CodexThread, isPinned: Boolean) {
        viewModelScope.launch { repository.setThreadPinned(thread.machineId, thread.threadId, isPinned) }
    }

    fun refresh() {
        if (isRefreshing) return
        if (!relayClient.connected) {
            isConnected = false
            statusMessage = "开发环境尚未连接；已缓存的项目仍可离线查看"
            return
        }
        viewModelScope.launch {
            isRefreshing = true
            statusMessage = null
            runCatching { relayClient.sync(200) }
                .onSuccess { result ->
                    statusMessage = if (result.gapDetected) "同步序列不连续，请稍后重试" else null
                }
                .onFailure { statusMessage = "暂时无法同步；当前展示本机缓存" }
            isConnected = relayClient.connected
            isRefreshing = false
        }
    }

    fun updateSearchQuery(value: String) {
        searchQuery = value
        searchJob?.cancel()
        if (value.isBlank()) {
            searchResults = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            searchResults = repository.search(value)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchQuery = ""
        searchResults = emptyList()
    }

    fun updateRecoveryKey(value: String) {
        recoveryKey = value
        statusMessage = null
    }

    fun updateRelayUrl(value: String) {
        relayUrl = value
        statusMessage = null
    }

    fun connect() {
        if (recoveryKey.isBlank() || isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            statusMessage = null
            runCatching { relayClient.connect(recoveryKey, relayUrl) }
                .onSuccess {
                    recoveryKey = ""
                    isConnected = true
                    statusMessage = "已连接，正在同步 Codex 项目"
                }
                .onFailure { statusMessage = it.message ?: "无法连接开发环境" }
            isRefreshing = false
            if (isConnected) refresh()
        }
    }

    fun disconnect() {
        relayClient.disconnect()
        isConnected = false
        recoveryKey = ""
        statusMessage = "已断开；本机缓存未删除"
    }

    fun startTask(project: CodexProject, text: String) {
        val prompt = text.trim()
        if (!project.existsOnDisk) {
            statusMessage = "开发机上的项目目录已不存在，无法新建任务"
            return
        }
        if (prompt.isEmpty() || isStartingTask || !relayClient.connected) return
        viewModelScope.launch {
            isStartingTask = true
            statusMessage = null
            runCatching {
                startTaskRequestId = relayClient.sendRuntimeCommand(
                    RuntimeCommandPayload(
                        command = "thread.start",
                        machineId = project.machineId,
                        cwd = project.canonicalRoot,
                        text = prompt,
                    )
                )
            }.onFailure {
                isStartingTask = false
                statusMessage = it.message ?: "无法创建任务"
            }
        }
    }

    fun consumeNewThreadTarget() {
        newThreadTarget = null
    }

    private companion object {
        const val DEFAULT_RELAY_URL = "https://relay.8-208-118-119.sslip.io"
    }
}

class CodexThreadVM(
    private val machineId: String,
    private val threadId: String,
    private val repository: CodexCatalogRepository,
    private val relayClient: WireRelayClient,
    private val filesManager: FilesManager,
) : ViewModel() {
    var detail by mutableStateOf(CodexThreadDetail(null, emptyList()))
        private set
    val inputState = ChatInputState()
    var runtimeCatalog by mutableStateOf<RuntimeCatalogPayload?>(null)
        private set
    var runtimeSettings by mutableStateOf(CodexRuntimeSettingsState())
        private set
    var selectedModelId by mutableStateOf<String?>(null)
        private set
    var selectedEffort by mutableStateOf<String?>(null)
        private set
    var fastMode by mutableStateOf(false)
        private set
    var selectedPermission by mutableStateOf<String?>(null)
        private set
    var selectedSkills by mutableStateOf<Set<String>>(emptySet())
        private set
    var isSending by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var showTakeoverConfirmation by mutableStateOf(false)
        private set
    var deleted by mutableStateOf(false)
        private set
    private val pendingRequests = mutableMapOf<String, String>()
    private val pendingDrafts = mutableMapOf<String, List<UIMessagePart>>()
    private var catalogJob: Job? = null
    private var catalogCwd: String? = null
    private var detailLoaded = false
    private var detailRetryCount = 0
    private var nextDetailRetryAt = 0L
    private val attachmentDownloads = mutableSetOf<String>()

    val canSend: Boolean get() = relayClient.connected && inputState.getContents().any { part ->
        part !is UIMessagePart.Text || part.text.isNotBlank()
    } && !isSending
    val isRunning: Boolean get() = detail.thread?.runtimeState == CodexRuntimeState.RUNNING

    init {
        viewModelScope.launch {
            val restored = repository.loadDraft(machineId, threadId)
            if (restored.isNotEmpty() && inputState.getContents().none { it !is UIMessagePart.Text || it.text.isNotBlank() }) {
                inputState.setContents(restored)
            }
            snapshotFlow { inputState.getContents() }.collectLatest { contents ->
                delay(500)
                repository.saveDraft(machineId, threadId, contents)
            }
        }
        viewModelScope.launch {
            repository.observeThreadDetail(machineId, threadId).collect {
                detail = it
                it.cwd?.let(::ensureRuntimeCatalog)
                scheduleAttachmentDownloads(it)
            }
        }
        viewModelScope.launch {
            while (true) {
                if (relayClient.connected) {
                    if (!detailLoaded && "thread.detail" !in pendingRequests.values && System.currentTimeMillis() >= nextDetailRetryAt) {
                        requestThreadDetail()
                    }
                    runCatching { relayClient.sync(200) }
                        .onSuccess { sync ->
                            if (sync.gapDetected) {
                                detailLoaded = false
                                nextDetailRetryAt = 0
                                statusMessage = "检测到消息缺口，正在重新同步完整历史"
                            } else if (statusMessage?.startsWith("连接暂时不可用") == true) {
                                statusMessage = null
                            }
                        }
                        .onFailure { error ->
                            if (statusMessage.isNullOrBlank()) statusMessage = "连接暂时不可用：${error.message ?: "稍后自动重试"}"
                        }
                    for ((requestId, command) in pendingRequests.toMap()) {
                        relayClient.consumeCommandResult(machineId, threadId, command, requestId)?.let { result ->
                            pendingRequests.remove(requestId)
                            val submittedDraft = pendingDrafts.remove(requestId)
                            if (!result.ok) {
                                statusMessage = result.error ?: "操作失败"
                                if (command == "thread.detail") scheduleDetailRetry()
                            } else {
                                if (command == "thread.detail") {
                                    detailLoaded = true
                                    detailRetryCount = 0
                                    if (statusMessage?.startsWith("历史同步失败") == true) statusMessage = null
                                }
                                if (submittedDraft != null && inputState.getContents() == submittedDraft) {
                                    inputState.clearInput()
                                }
                                if (command == "turn.steer") statusMessage = "补充要求已发送"
                                if (command == "thread.delete") deleted = true
                            }
                            isSending = false
                        }
                    }
                }
                delay(1_500)
            }
        }
    }

    fun send() {
        if (!canSend) return
        if (detail.thread?.runtimeState == CodexRuntimeState.UNKNOWN) {
            showTakeoverConfirmation = true
            return
        }
        sendConfirmed(false)
    }

    fun confirmTakeover() {
        showTakeoverConfirmation = false
        sendConfirmed(true)
    }

    fun dismissTakeover() {
        showTakeoverConfirmation = false
    }

    private fun sendConfirmed(confirmedUnknown: Boolean) {
        val contents = inputState.getContents()
        if (contents.none { it !is UIMessagePart.Text || it.text.isNotBlank() } || isSending) return
        viewModelScope.launch {
            isSending = true
            statusMessage = null
            val command = if (isRunning) "turn.steer" else "turn.start"
            runCatching {
                val inputs = buildInputs(contents)
                val text = contents.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }.trim()
                val requestId = relayClient.sendRuntimeCommand(
                    RuntimeCommandPayload(
                        command = command,
                        machineId = machineId,
                        threadId = threadId,
                        text = text,
                        input = inputs,
                        confirmedUnknown = confirmedUnknown,
                        model = selectedModelId,
                        effort = selectedEffort,
                        serviceTier = "priority".takeIf { fastMode },
                        permissions = selectedPermission,
                    )
                )
                pendingRequests[requestId] = command
                pendingDrafts[requestId] = contents
            }.onFailure {
                statusMessage = it.message ?: "无法发送"
                isSending = false
            }
        }
    }

    fun selectModel(modelId: String) {
        val model = runtimeCatalog?.models?.firstOrNull { it.id == modelId || it.model == modelId } ?: return
        selectedModelId = model.id
        if (selectedEffort !in model.supportedReasoningEfforts.map { it.reasoningEffort }) {
            selectedEffort = model.defaultReasoningEffort
        }
        fastMode = fastMode && model.serviceTiers.any { it.id == "priority" }
    }

    fun selectEffort(effort: String) {
        if (selectedModel?.supportedReasoningEfforts?.any { it.reasoningEffort == effort } == true) {
            selectedEffort = effort
        }
    }

    private suspend fun requestThreadDetail() {
        runCatching {
            val requestId = relayClient.sendRuntimeCommand(RuntimeCommandPayload("thread.detail", machineId, threadId))
            pendingRequests[requestId] = "thread.detail"
        }.onFailure {
            statusMessage = "历史同步失败，正在自动重试：${it.message ?: "网络不可用"}"
            scheduleDetailRetry()
        }
    }

    private fun scheduleDetailRetry() {
        detailRetryCount = (detailRetryCount + 1).coerceAtMost(5)
        nextDetailRetryAt = System.currentTimeMillis() + (1_000L shl detailRetryCount).coerceAtMost(30_000L)
        if (statusMessage?.startsWith("历史同步失败") != true) {
            statusMessage = "历史同步失败，正在自动重试：${statusMessage ?: "未知错误"}"
        }
    }

    fun updateFastMode(enabled: Boolean) {
        fastMode = enabled && selectedModel?.serviceTiers?.any { it.id == "priority" } == true
    }

    fun selectPermission(profileId: String) {
        if (runtimeCatalog?.permissionProfiles?.any { it.id == profileId && it.allowed } == true) {
            selectedPermission = profileId
        }
    }

    fun toggleSkill(skill: CodexSkillOption) {
        selectedSkills = if (skill.name in selectedSkills) selectedSkills - skill.name else selectedSkills + skill.name
    }

    val selectedModel: CodexModelOption?
        get() = runtimeCatalog?.models?.firstOrNull { it.id == selectedModelId || it.model == selectedModelId }

    private fun ensureRuntimeCatalog(cwd: String) {
        if (catalogCwd == cwd) return
        catalogCwd = cwd
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            launch {
                repository.observeRuntimeCatalog(machineId, cwd).collect { catalog ->
                    runtimeCatalog = catalog
                    catalog?.let(::applyCatalogDefaults)
                }
            }
            if (relayClient.connected) {
                runCatching {
                    val requestId = relayClient.sendRuntimeCommand(
                        RuntimeCommandPayload(command = "runtime.catalog", machineId = machineId, cwd = cwd)
                    )
                    val result = relayClient.awaitCommandResult(machineId, null, "runtime.catalog", requestId)
                    if (!result.ok) error(result.error ?: "Codex 运行参数读取失败")
                }.onFailure { statusMessage = it.message ?: "无法读取 Codex 运行参数" }
            }
        }
        viewModelScope.launch {
            repository.observeRuntimeSettings(machineId, threadId).collect { settings ->
                runtimeSettings = settings
                settings.model?.let { model ->
                    if (runtimeCatalog?.models?.any { it.id == model || it.model == model } == true) selectModel(model)
                }
                settings.effort?.let { effort -> if (selectedModel?.supportedReasoningEfforts?.any { it.reasoningEffort == effort } == true) selectedEffort = effort }
                if (settings.serviceTier != null) fastMode = settings.serviceTier == "priority"
                settings.permissions?.let { selectedPermission = it }
            }
        }
    }

    private fun applyCatalogDefaults(catalog: RuntimeCatalogPayload) {
        if (selectedModelId == null) {
            val model = catalog.models.firstOrNull { it.isDefault } ?: catalog.models.firstOrNull()
            model?.let { selectModel(it.id) }
        }
        if (selectedPermission == null) {
            selectedPermission = catalog.permissionProfiles.firstOrNull { it.allowed }?.id
        }
    }

    private suspend fun buildInputs(parts: List<UIMessagePart>): List<CodexInputPayload> {
        val inputs = mutableListOf<CodexInputPayload>()
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> if (part.text.isNotBlank()) inputs += CodexInputPayload("text", text = part.text)
                is UIMessagePart.Image -> {
                    if (part.url.startsWith("http://") || part.url.startsWith("https://") || part.url.startsWith("data:")) {
                        inputs += CodexInputPayload("image", url = part.url, detail = "auto")
                    } else {
                        val path = upload(part.url, "image", "image/*")
                        inputs += CodexInputPayload("localImage", path = path, detail = "auto")
                    }
                }
                is UIMessagePart.Document -> {
                    val path = upload(part.url, part.fileName, part.mime)
                    inputs += CodexInputPayload("mention", path = path, name = part.fileName)
                }
                else -> Unit
            }
        }
        runtimeCatalog?.skills.orEmpty()
            .filter { it.enabled && it.name in selectedSkills }
            .forEach { skill -> inputs += CodexInputPayload("skill", path = skill.path, name = skill.name) }
        return inputs
    }

    private fun scheduleAttachmentDownloads(detail: CodexThreadDetail) {
        detail.turns.asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.rawType == "userMessage" }
            .flatMap { item -> (item.raw["content"] as? JsonArray).orEmpty().asSequence() }
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "localImage" }
            .mapNotNull { it["path"]?.jsonPrimitive?.contentOrNull }
            .filter { it !in detail.attachments && attachmentDownloads.add(it) }
            .forEach { remotePath ->
                viewModelScope.launch {
                    var lastError: Throwable? = null
                    repeat(3) { attempt ->
                        val downloaded = runCatching { downloadAttachment(remotePath) }
                            .onFailure { lastError = it }
                            .isSuccess
                        if (downloaded) return@launch
                        delay((attempt + 1) * 2_000L)
                    }
                    attachmentDownloads.remove(remotePath)
                    statusMessage = "图片暂时无法从开发机取回：${lastError?.message ?: remotePath.substringAfterLast('\\')}"
                }
            }
    }

    private suspend fun downloadAttachment(remotePath: String) {
        val chunks = mutableListOf<ByteArray>()
        var expectedChunks = 1
        var expectedHash: String? = null
        var fileName = remotePath.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image" }
        var mime = "application/octet-stream"
        var index = 0
        do {
            val requestId = relayClient.sendRuntimeCommand(
                RuntimeCommandPayload(
                    command = "attachment.download",
                    machineId = machineId,
                    threadId = threadId,
                    path = remotePath,
                    chunkIndex = index,
                )
            )
            val result = relayClient.awaitCommandResult(machineId, threadId, "attachment.download", requestId)
            if (!result.ok) error(result.error ?: "图片下载失败")
            val payload = requireNotNull(result.result) { "图片下载没有返回内容" }
            val returnedIndex = payload["chunkIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            require(returnedIndex == index) { "图片下载分片顺序错误" }
            val count = payload["chunkCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: error("图片下载分片数量缺失")
            require(count in 1..64 && (index == 0 || count == expectedChunks)) { "图片下载分片元数据冲突" }
            expectedChunks = count
            val hash = payload["sha256"]?.jsonPrimitive?.contentOrNull ?: error("图片下载哈希缺失")
            require(expectedHash == null || expectedHash == hash) { "图片下载哈希冲突" }
            expectedHash = hash
            fileName = payload["fileName"]?.jsonPrimitive?.contentOrNull ?: fileName
            mime = payload["mime"]?.jsonPrimitive?.contentOrNull ?: mime
            chunks += Base64.getDecoder().decode(payload["contentBase64"]?.jsonPrimitive?.contentOrNull ?: error("图片下载内容缺失"))
            index += 1
        } while (index < expectedChunks)
        val bytes = chunks.fold(ByteArray(0)) { result, chunk -> result + chunk }
        require(bytes.size.toLong() <= MAX_ATTACHMENT_BYTES) { "图片下载超过 20 MiB" }
        val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actualHash == expectedHash) { "图片下载哈希校验失败" }
        val localUri = filesManager.createChatFilesByByteArrays(listOf(bytes)).singleOrNull()
            ?: error("图片无法保存到本机")
        repository.rememberAttachment(machineId, threadId, remotePath, localUri.toString(), fileName, mime)
        attachmentDownloads.remove(remotePath)
    }

    private suspend fun upload(url: String, fallbackName: String, mime: String): String {
        val file = url.toUri().toFile()
        require(file.exists()) { "附件已不存在：${file.name}" }
        require(file.length() <= MAX_ATTACHMENT_BYTES) { "附件不能超过 20 MiB" }
        val bytes = file.readBytes()
        val attachmentId = UUID.randomUUID().toString().replace('-', '_')
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val chunks = bytes.asList().chunked(ATTACHMENT_CHUNK_BYTES).map { it.toByteArray() }
        var localPath: String? = null
        chunks.forEachIndexed { index, chunk ->
            val requestId = relayClient.sendRuntimeCommand(
                RuntimeCommandPayload(
                    command = "attachment.upload",
                    machineId = machineId,
                    attachmentId = attachmentId,
                    fileName = file.name.ifBlank { fallbackName },
                    mime = mime,
                    chunkIndex = index,
                    chunkCount = chunks.size,
                    contentBase64 = Base64.getEncoder().encodeToString(chunk),
                    sha256 = sha256,
                )
            )
            val result = relayClient.awaitCommandResult(machineId, null, "attachment.upload", requestId)
            if (!result.ok) error(result.error ?: "附件上传失败")
            localPath = result.result?.get("localPath")?.jsonPrimitive?.contentOrNull ?: localPath
        }
        val remotePath = requireNotNull(localPath) { "附件上传没有返回开发机路径" }
        repository.rememberAttachment(
            machineId = machineId,
            threadId = threadId,
            remotePath = remotePath,
            localUri = url,
            fileName = file.name.ifBlank { fallbackName },
            mime = mime,
        )
        return remotePath
    }

    fun interrupt() = command("turn.interrupt")
    fun archive() = command("thread.archive")
    fun unarchive() = command("thread.unarchive")
    fun delete() = command("thread.delete")
    fun setPinned(isPinned: Boolean) {
        viewModelScope.launch { repository.setThreadPinned(machineId, threadId, isPinned) }
    }

    fun resolveApproval(approvalId: String, decision: String) = command(
        "approval.resolve",
        approvalId = approvalId,
        decision = decision,
    )

    fun resolveInteraction(approvalId: String, answer: String) = command(
        command = "interaction.resolve",
        approvalId = approvalId,
        answer = answer,
    )

    private fun command(
        command: String,
        approvalId: String? = null,
        decision: String? = null,
        answer: String? = null,
    ) {
        if (!relayClient.connected || isSending) return
        viewModelScope.launch {
            isSending = true
            statusMessage = null
            runCatching {
                val requestId = relayClient.sendRuntimeCommand(
                    RuntimeCommandPayload(
                        command = command,
                        machineId = machineId,
                        threadId = threadId,
                        approvalId = approvalId,
                        decision = decision,
                        answer = answer,
                    )
                )
                pendingRequests[requestId] = command
            }.onFailure {
                statusMessage = it.message ?: "操作失败"
                isSending = false
            }
        }
    }

    companion object {
        private const val ATTACHMENT_CHUNK_BYTES = 384 * 1024
        private const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024
    }
}
