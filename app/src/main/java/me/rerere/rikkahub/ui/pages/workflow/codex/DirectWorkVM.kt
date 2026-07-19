package me.rerere.rikkahub.ui.pages.workflow.codex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.work.AppServerConnectionPhase
import me.rerere.rikkahub.data.work.AppServerAttachedFile
import me.rerere.rikkahub.data.work.AppServerAttachmentManifest
import me.rerere.rikkahub.data.work.AppServerCommandInputs
import me.rerere.rikkahub.data.work.AppServerEndpoint
import me.rerere.rikkahub.data.work.AppServerJsonRpcClient
import me.rerere.rikkahub.data.work.AppServerRequest
import me.rerere.rikkahub.data.work.AppServerRuntimeMapper
import me.rerere.rikkahub.data.work.AppServerThreadReducer
import me.rerere.rikkahub.data.work.BundledCodexCatalog
import me.rerere.rikkahub.data.work.SupervisorAttachmentClient
import me.rerere.rikkahub.data.work.WorkConnectionCredentials
import me.rerere.rikkahub.data.work.WorkConnectionStore
import me.rerere.rikkahub.data.work.WorkRepositoryConfig
import me.rerere.rikkahub.data.work.WorkRepositoryPreferences
import me.rerere.rikkahub.data.work.WorkUiStore
import me.rerere.rikkahub.data.workflow.codex.CodexApproval
import me.rerere.rikkahub.data.workflow.codex.CodexAttachment
import me.rerere.rikkahub.data.workflow.codex.CodexCatalogCapabilities
import me.rerere.rikkahub.data.workflow.codex.CodexPermissionProfile
import me.rerere.rikkahub.data.workflow.codex.CodexModelOption
import me.rerere.rikkahub.data.workflow.codex.CodexReasoningOption
import me.rerere.rikkahub.data.workflow.codex.CodexRuntimeSettingsState
import me.rerere.rikkahub.data.workflow.codex.CodexServiceTier
import me.rerere.rikkahub.data.workflow.codex.CodexSkillInterface
import me.rerere.rikkahub.data.workflow.codex.CodexSkillOption
import me.rerere.rikkahub.data.workflow.codex.CodexThreadDetail
import me.rerere.rikkahub.data.workflow.codex.RuntimeCatalogPayload
import me.rerere.rikkahub.ui.hooks.ChatInputState

class DirectWorkVM(
    private val repositoryId: String,
    private val client: AppServerJsonRpcClient,
    private val connectionStore: WorkConnectionStore,
    private val workUiStore: WorkUiStore,
    private val attachmentClient: SupervisorAttachmentClient,
) : ViewModel() {
    val inputState = ChatInputState()
    var repository by mutableStateOf<WorkRepositoryConfig?>(null)
        private set
    var detail by mutableStateOf(CodexThreadDetail(null, emptyList()))
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var connected by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var selectedModelId by mutableStateOf(BundledCodexCatalog.models.firstOrNull { it.isDefault }?.id)
        private set
    var selectedEffort by mutableStateOf(BundledCodexCatalog.models.firstOrNull { it.isDefault }?.defaultReasoningEffort)
        private set
    var fastMode by mutableStateOf(false)
        private set
    var selectedPermission by mutableStateOf("default")
        private set
    var runtimeCatalog by mutableStateOf(defaultCatalog(""))
        private set
    var runtimeSettings by mutableStateOf(CodexRuntimeSettingsState())
        private set

    private var connection: WorkConnectionCredentials? = null
    private var currentTurnId: String? = null
    private val pendingRequests = mutableMapOf<String, AppServerRequest>()

    val selectedModel get() = runtimeCatalog.models.firstOrNull { it.id == selectedModelId }
    val isRunning get() = detail.thread?.runtimeState?.name == "RUNNING"
    val canSend get() = !sending && connected && repository != null && selectedModelId != null &&
        (!isRunning || currentTurnId != null) && !inputState.isEmpty()

    init {
        repository = workUiStore.state.value.repositories.firstOrNull { it.id == repositoryId }
        repository?.preferences?.let(::applyPreferences)
        repository?.draft?.takeIf(String::isNotEmpty)?.let(inputState::setMessageText)
        runtimeCatalog = runtimeCatalog.copy(cwd = repository?.path.orEmpty())
        viewModelScope.launch {
            client.state.collect { state ->
                connected = state.phase == AppServerConnectionPhase.READY
                if (state.phase == AppServerConnectionPhase.FAILED || state.phase == AppServerConnectionPhase.DISCONNECTED) {
                    statusMessage = state.error ?: "与 Codex 的连接已断开"
                }
            }
        }
        viewModelScope.launch {
            workUiStore.state.collectLatest { state ->
                val next = state.repositories.firstOrNull { it.id == repositoryId }
                repository = next
                next?.preferences?.let(::applyPreferences)
                runtimeCatalog = runtimeCatalog.copy(cwd = next?.path.orEmpty())
            }
        }
        viewModelScope.launch {
            client.notifications.collect { notification ->
                detail = AppServerThreadReducer.apply(detail, notification)
                val notificationThreadId = (notification.params as? JsonObject)?.string("threadId")
                if (notificationThreadId == null || notificationThreadId == detail.thread?.threadId) {
                    runtimeSettings = AppServerRuntimeMapper.applyNotification(runtimeSettings, notification)
                }
                if (notification.method == "turn/started") {
                    currentTurnId = (notification.params as? JsonObject)?.get("turn")?.jsonObject?.string("id")
                }
                if (notification.method == "turn/completed") currentTurnId = null
            }
        }
        viewModelScope.launch {
            client.serverRequests.collect { request ->
                val params = request.params as? JsonObject ?: JsonObject(emptyMap())
                if (params.string("threadId") != detail.thread?.threadId) return@collect
                val id = request.id.toString().trim('"')
                pendingRequests[id] = request
                detail = AppServerThreadReducer.applyServerRequest(detail, request)
                detail = detail.copy(
                    approvals = detail.approvals + CodexApproval(
                        approvalId = id,
                        kind = if (request.method == "item/tool/requestUserInput") "user_input" else "approval",
                        summary = params.string("reason")
                            ?: params.string("command")
                            ?: params.string("tool")
                            ?: "Codex 请求你的确认",
                        createdAt = System.currentTimeMillis(),
                        payload = params,
                    )
                )
            }
        }
        connect()
    }

    fun connect() {
        if (client.state.value.phase == AppServerConnectionPhase.CONNECTING ||
            client.state.value.phase == AppServerConnectionPhase.INITIALIZING
        ) return
        viewModelScope.launch {
            val credentials = connectionStore.load().let { stored ->
                stored.connections.firstOrNull { it.connectionId == stored.activeConnectionId }
            }
            if (credentials == null) {
                statusMessage = "请先在设置的 Work 卡片中配置开发机连接"
                return@launch
            }
            connection = credentials
            if (client.state.value.phase == AppServerConnectionPhase.READY) {
                connected = true
                statusMessage = null
                refreshCatalog()
                repository?.currentThreadId?.let { resume(it) }
                return@launch
            }
            statusMessage = "正在连接 Codex…"
            runCatching {
                client.connect(
                    AppServerEndpoint(
                        webSocketUrl = credentials.appServerUrl,
                        bearerToken = credentials.appServerToken,
                        allowInsecureLoopback = credentials.appServerUrl.startsWith("ws://127.0.0.1") ||
                            credentials.appServerUrl.startsWith("ws://localhost"),
                    )
                )
                connected = true
                statusMessage = null
                refreshCatalog()
                repository?.currentThreadId?.let { resume(it) }
            }.onFailure {
                connected = false
                statusMessage = it.message ?: "无法连接 Codex"
            }
        }
    }

    fun send() {
        if (!canSend) return
        val contents = inputState.getContents()
        viewModelScope.launch {
            sending = true
            statusMessage = null
            runCatching {
                val threadId = detail.thread?.threadId ?: startThread()
                val input = buildInput(contents)
                val response = client.request(
                    if (isRunning) "turn/steer" else "turn/start",
                    if (isRunning) buildJsonObject {
                        put("threadId", threadId)
                        put("expectedTurnId", requireNotNull(currentTurnId))
                        put("input", input)
                    } else buildJsonObject {
                        put("threadId", threadId)
                        put("input", input)
                        selectedModelId?.let { put("model", it) }
                        selectedEffort?.let { put("effort", it) }
                        put("serviceTier", if (fastMode) JsonPrimitive("priority") else kotlinx.serialization.json.JsonNull)
                        put("approvalPolicy", approvalPolicy())
                        put("sandboxPolicy", sandboxPolicy())
                    },
                ).jsonObject
                val turn = response["turn"] as? JsonObject
                if (turn != null) {
                    detail = AppServerThreadReducer.apply(
                        detail,
                        me.rerere.rikkahub.data.work.AppServerNotification(
                            "turn/started",
                            buildJsonObject {
                                put("threadId", threadId)
                                put("turn", turn)
                            },
                        ),
                    )
                    currentTurnId = turn.string("id")
                }
                inputState.clearInput()
                persistDraft("")
            }.onFailure { statusMessage = it.message ?: "无法发送" }
            sending = false
        }
    }

    fun interrupt() {
        val threadId = detail.thread?.threadId ?: return
        val turnId = currentTurnId ?: return
        viewModelScope.launch {
            runCatching {
                client.request("turn/interrupt", buildJsonObject { put("threadId", threadId); put("turnId", turnId) })
            }.onFailure { statusMessage = it.message ?: "无法停止" }
        }
    }

    fun newThread() {
        val repo = repository ?: return
        if (isRunning) {
            statusMessage = "请先停止当前任务，再新建 Work 对话"
            return
        }
        detail = CodexThreadDetail(null, emptyList())
        currentTurnId = null
        runtimeSettings = runtimeSettings.copy(
            usedTokens = null,
            contextWindow = null,
            updatedAt = System.currentTimeMillis(),
        )
        inputState.clearInput()
        persistDraft("")
        if (!connected) {
            statusMessage = "连接 Codex 后才能新建对话"
            return
        }
        sending = true
        statusMessage = "正在新建 Work 对话…"
        viewModelScope.launch {
            workUiStore.clearCurrentThread(repo.id)
            runCatching { startThread() }
                .onSuccess { statusMessage = null }
                .onFailure { statusMessage = it.message ?: "无法新建 Work 对话" }
            sending = false
        }
    }

    fun selectModel(modelId: String) {
        val model = runtimeCatalog.models.firstOrNull { it.id == modelId } ?: return
        selectedModelId = model.id
        selectedEffort = repository?.preferences?.effortFor(model.id)
            ?.takeIf { effort -> model.supportedReasoningEfforts.any { it.reasoningEffort == effort } }
            ?: model.defaultReasoningEffort
        fastMode = fastMode && model.serviceTiers.any { it.id == "priority" }
        updateOptimisticRuntimeSettings()
        persistPreferences()
    }

    fun selectEffort(effort: String) {
        if (selectedModel?.supportedReasoningEfforts?.any { it.reasoningEffort == effort } == true) {
            selectedEffort = effort
            updateOptimisticRuntimeSettings()
            persistPreferences()
        }
    }

    fun updateFastMode(enabled: Boolean) {
        fastMode = enabled && selectedModel?.serviceTiers?.any { it.id == "priority" } == true
        updateOptimisticRuntimeSettings()
        persistPreferences()
    }

    fun selectPermission(profile: String) {
        if (profile in setOf("read-only", "default", "full-access")) {
            selectedPermission = profile
            updateOptimisticRuntimeSettings()
            persistPreferences()
        }
    }

    fun persistDraft(text: String) {
        val repo = repository ?: return
        if (repo.draft == text) return
        viewModelScope.launch { workUiStore.updateRepositorySession(repo.id, draft = text) }
    }

    fun compactThread() {
        val threadId = detail.thread?.threadId ?: run {
            statusMessage = "当前仓库还没有可压缩的对话"
            return
        }
        if (isRunning) {
            statusMessage = "请先停止当前任务，再压缩历史"
            return
        }
        viewModelScope.launch {
            statusMessage = "正在压缩上下文…"
            runCatching {
                client.request("thread/compact/start", buildJsonObject { put("threadId", threadId) })
            }.onSuccess {
                statusMessage = "上下文压缩已开始"
            }.onFailure {
                statusMessage = it.message ?: "无法压缩上下文"
            }
        }
    }

    fun resolveApproval(approvalId: String, decision: String) {
        val request = pendingRequests.remove(approvalId) ?: return
        client.respond(request, buildJsonObject { put("decision", decision) })
        detail = detail.copy(approvals = detail.approvals.filterNot { it.approvalId == approvalId })
    }

    fun resolveInteraction(approvalId: String, answer: String) {
        val request = pendingRequests.remove(approvalId) ?: return
        val incoming = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(answer).jsonObject }
            .getOrNull()
        val incomingAnswers = incoming?.get("answers") as? JsonObject ?: JsonObject(emptyMap())
        client.respond(request, buildJsonObject {
            put("answers", buildJsonObject {
                incomingAnswers.forEach { (questionId, value) ->
                    val values = when (value) {
                        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        is JsonPrimitive -> value.contentOrNull?.let(::listOf).orEmpty()
                        is JsonObject -> (value["answers"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            .orEmpty()
                        else -> emptyList()
                    }
                    put(questionId, buildJsonObject {
                        put("answers", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
        })
        detail = detail.copy(approvals = detail.approvals.filterNot { it.approvalId == approvalId })
    }

    fun cancelInteraction(approvalId: String) {
        val request = pendingRequests.remove(approvalId) ?: return
        client.respondError(request, -32800, "User cancelled")
        detail = detail.copy(approvals = detail.approvals.filterNot { it.approvalId == approvalId })
    }

    private suspend fun startThread(): String {
        val repo = requireNotNull(repository)
        val result = client.request("thread/start", buildJsonObject {
            put("cwd", repo.path)
            selectedModelId?.let { put("model", it) }
            put("serviceTier", if (fastMode) JsonPrimitive("priority") else kotlinx.serialization.json.JsonNull)
            put("approvalPolicy", approvalPolicy())
            put("sandbox", sandboxMode())
        })
        detail = AppServerThreadReducer.snapshot(result, repo.id, repo.machineId ?: "direct")
        val threadId = requireNotNull(detail.thread?.threadId)
        workUiStore.updateRepositorySession(repo.id, currentThreadId = threadId)
        return threadId
    }

    private suspend fun resume(threadId: String) {
        val repo = repository ?: return
        runCatching {
            client.request("thread/resume", buildJsonObject { put("threadId", threadId) })
            val snapshot = client.request("thread/read", buildJsonObject {
                put("threadId", threadId)
                put("includeTurns", true)
            })
            detail = AppServerThreadReducer.snapshot(snapshot, repo.id, repo.machineId ?: "direct")
        }.onFailure { statusMessage = "历史同步失败：${it.message ?: "未知错误"}" }
    }

    private suspend fun refreshCatalog() {
        val repo = repository ?: return
        val modelCall = viewModelScope.async {
            runCatching { client.request("model/list", buildJsonObject { put("includeHidden", false) }) }.getOrNull()
        }
        val skillsCall = viewModelScope.async {
            runCatching {
                client.request("skills/list", buildJsonObject {
                    put("cwds", buildJsonArray { add(JsonPrimitive(repo.path)) })
                    put("forceReload", false)
                })
            }.getOrNull()
        }
        val pluginsCall = viewModelScope.async {
            runCatching {
                client.request("plugin/list", buildJsonObject {
                    put("cwds", buildJsonArray { add(JsonPrimitive(repo.path)) })
                })
            }.getOrNull()
        }
        val appsCall = viewModelScope.async {
            runCatching {
                client.request("app/list", buildJsonObject {
                    put("limit", 100)
                    put("forceRefetch", false)
                    detail.thread?.threadId?.let { put("threadId", it) }
                })
            }.getOrNull()
        }
        val models = AppServerRuntimeMapper.models(modelCall.await())
        val skills = ((skillsCall.await() as? JsonObject)?.get("data") as? JsonArray)
            .orEmpty()
            .flatMap { entry -> ((entry as? JsonObject)?.get("skills") as? JsonArray).orEmpty() }
            .mapNotNull { element ->
                val raw = element as? JsonObject ?: return@mapNotNull null
                CodexSkillOption(
                    name = raw.string("name") ?: return@mapNotNull null,
                    path = raw.string("path") ?: return@mapNotNull null,
                    description = raw.string("description").orEmpty(),
                    shortDescription = raw.string("shortDescription"),
                    enabled = raw["enabled"]?.toString()?.toBooleanStrictOrNull() ?: true,
                    scope = raw["scope"]?.toString().orEmpty(),
                    interfaceInfo = (raw["interface"] as? JsonObject)?.let {
                        CodexSkillInterface(it.string("displayName"), it.string("shortDescription"), it.string("defaultPrompt"))
                    },
                )
            }
        val plugins = AppServerRuntimeMapper.plugins(pluginsCall.await())
        val apps = AppServerRuntimeMapper.apps(appsCall.await())
        val mergedModels = BundledCodexCatalog.merge(models).withSelectedModelFallback()
        runtimeCatalog = defaultCatalog(repo.path).copy(
            models = mergedModels,
            skills = skills,
            plugins = plugins,
            apps = apps,
            generatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun buildInput(contents: List<UIMessagePart>): JsonArray {
        val connection = requireNotNull(connection)
        val uploadedDocuments = contents.filterIsInstance<UIMessagePart.Document>().associateWith { part ->
            attachmentClient.upload(connection, part.url.toUri(), part.fileName, part.mime)
        }
        val uploadedImages = contents.filterIsInstance<UIMessagePart.Image>().associateWith { part ->
            attachmentClient.upload(connection, part.url.toUri(), "image.jpg", "image/jpeg")
        }
        detail = detail.copy(attachments = detail.attachments + buildMap {
            uploadedDocuments.forEach { (part, receipt) ->
                put(
                    receipt.localPath,
                    CodexAttachment(receipt.localPath, part.url, receipt.fileName, receipt.mime),
                )
            }
            uploadedImages.forEach { (part, receipt) ->
                put(
                    receipt.localPath,
                    CodexAttachment(receipt.localPath, part.url, receipt.fileName, receipt.mime),
                )
            }
        })
        return buildJsonArray {
            contents.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> add(buildJsonObject {
                        put("type", "text")
                        put("text", part.text)
                        put("text_elements", JsonArray(emptyList()))
                    })
                    is UIMessagePart.Image -> {
                        val receipt = requireNotNull(uploadedImages[part])
                        add(buildJsonObject { put("type", "localImage"); put("path", receipt.localPath); put("detail", "auto") })
                    }
                    is UIMessagePart.Document -> Unit
                    else -> Unit
                }
            }
            if (uploadedDocuments.isNotEmpty()) {
                val manifest = AppServerAttachmentManifest.append(
                    text = "",
                    files = uploadedDocuments.map { (part, receipt) ->
                        AppServerAttachedFile(part.fileName, receipt.localPath, part.mime)
                    },
                )
                add(buildJsonObject {
                    put("type", "text")
                    put("text", manifest)
                    put("text_elements", JsonArray(emptyList()))
                })
            }
            val text = contents.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            AppServerCommandInputs.resolve(text, runtimeCatalog).forEach(::add)
        }
    }

    private fun applyPreferences(preferences: WorkRepositoryPreferences) {
        preferences.model?.let { selectedModelId = it }
        preferences.effortFor(selectedModelId)?.let { selectedEffort = it }
        selectedPermission = preferences.permission ?: "default"
        fastMode = preferences.fastMode
        runtimeCatalog = runtimeCatalog.copy(models = runtimeCatalog.models.withSelectedModelFallback())
        updateOptimisticRuntimeSettings()
    }

    private fun persistPreferences() {
        val repo = repository ?: return
        val preferences = repo.preferences.copy(
            model = selectedModelId,
            permission = selectedPermission,
            fastMode = fastMode,
        ).withEffort(selectedModelId, selectedEffort)
        viewModelScope.launch {
            workUiStore.updateRepositorySession(
                repo.id,
                preferences = preferences,
            )
        }
    }

    private fun updateOptimisticRuntimeSettings() {
        runtimeSettings = runtimeSettings.copy(
            model = selectedModelId,
            effort = selectedEffort,
            serviceTier = if (fastMode) "priority" else null,
            permissions = selectedPermission,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun List<CodexModelOption>.withSelectedModelFallback(): List<CodexModelOption> {
        val id = selectedModelId ?: return this
        if (any { it.id == id }) return this
        val effort = selectedEffort ?: "medium"
        return this + CodexModelOption(
            id = id,
            model = id,
            displayName = id,
            description = "上次选择的 Codex 模型；目录刷新后会自动更新能力。",
            defaultReasoningEffort = effort,
            supportedReasoningEfforts = listOf(CodexReasoningOption(effort)),
            serviceTiers = if (fastMode) listOf(CodexServiceTier("priority", "Fast")) else emptyList(),
        )
    }

    private fun approvalPolicy() = if (selectedPermission == "full-access") JsonPrimitive("never") else JsonPrimitive("on-request")
    private fun sandboxMode() = JsonPrimitive(when (selectedPermission) {
        "read-only" -> "read-only"
        "full-access" -> "danger-full-access"
        else -> "workspace-write"
    })
    private fun sandboxPolicy() = when (selectedPermission) {
        "read-only" -> buildJsonObject { put("type", "readOnly"); put("networkAccess", false) }
        "full-access" -> buildJsonObject { put("type", "dangerFullAccess") }
        else -> buildJsonObject {
            put("type", "workspaceWrite")
            put("writableRoots", JsonArray(emptyList()))
            put("networkAccess", true)
            put("excludeTmpdirEnvVar", false)
            put("excludeSlashTmp", false)
        }
    }

    private fun defaultCatalog(cwd: String) = RuntimeCatalogPayload(
        machineId = "direct",
        cwd = cwd,
        models = BundledCodexCatalog.models,
        permissionProfiles = listOf(
            CodexPermissionProfile("read-only", true, "只读仓库"),
            CodexPermissionProfile("default", true, "工作区写入，按需审批"),
            CodexPermissionProfile("full-access", true, "完全访问"),
        ),
        capabilities = CodexCatalogCapabilities(),
        generatedAt = System.currentTimeMillis(),
    )
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
