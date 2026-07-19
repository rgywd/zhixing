package me.rerere.rikkahub.ui.pages.workflow.codex

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.random.Random
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
import me.rerere.rikkahub.data.work.AppServerBackoffPolicy
import me.rerere.rikkahub.data.work.AppServerCompatibility
import me.rerere.rikkahub.data.work.AppServerCompatibilityGate
import me.rerere.rikkahub.data.work.AppServerCompatibilityLevel
import me.rerere.rikkahub.data.work.AppServerCommandInputs
import me.rerere.rikkahub.data.work.AppServerEndpoint
import me.rerere.rikkahub.data.work.AppServerJsonRpcClient
import me.rerere.rikkahub.data.work.AppServerOptionRollback
import me.rerere.rikkahub.data.work.AppServerRequest
import me.rerere.rikkahub.data.work.AppServerRuntimeMapper
import me.rerere.rikkahub.data.work.AppServerSnapshotBuffer
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
import me.rerere.rikkahub.data.workflow.codex.CodexCatalogRepository
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
    private val catalogRepository: CodexCatalogRepository,
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
    var compatibility by mutableStateOf(
        AppServerCompatibility(AppServerCompatibilityLevel.READ_ONLY, "正在核对 Codex 兼容性")
    )
        private set

    private var connection: WorkConnectionCredentials? = null
    private var connectedConnectionId: String? = null
    private var currentTurnId: String? = null
    private var acceptedPreferences = WorkRepositoryPreferences()
    private var reconnectJob: Job? = null
    private var cacheJob: Job? = null
    private var reconnectAttempt = 0
    private val reconnectPolicy = AppServerBackoffPolicy()
    private val pendingRequests = mutableMapOf<String, PendingServerRequest>()
    private val snapshotBuffer = AppServerSnapshotBuffer()
    private var writeGeneration = 0L

    val selectedModel get() = runtimeCatalog.models.firstOrNull { it.id == selectedModelId }
    val isRunning get() = detail.thread?.runtimeState?.name == "RUNNING"
    private val writable get() = directWorkWritable(connected, compatibility.level)
    val inputRestriction: String?
        get() = directWorkInputRestriction(
            contents = inputState.getContents(),
            modelInputModalities = selectedModel?.inputModalities.orEmpty(),
            compatibilityLevel = compatibility.level,
        )
    val canSend get() = !sending && writable && repository != null && selectedModelId != null &&
        inputRestriction == null && (!isRunning || currentTurnId != null) && !inputState.isEmpty()

    init {
        repository = workUiStore.state.value.repositories.firstOrNull { it.id == repositoryId }
        repository?.preferences?.let(::applyPreferences)
        acceptedPreferences = currentPreferences()
        repository?.draft?.takeIf(String::isNotEmpty)?.let(inputState::setMessageText)
        runtimeCatalog = runtimeCatalog.copy(cwd = repository?.path.orEmpty())
        viewModelScope.launch {
            client.state.collect { state ->
                connected = state.phase == AppServerConnectionPhase.READY
                when (state.phase) {
                    AppServerConnectionPhase.READY -> {
                        reconnectAttempt = 0
                        reconnectJob?.cancel()
                        reconnectJob = null
                    }
                    AppServerConnectionPhase.FAILED,
                    AppServerConnectionPhase.DISCONNECTED -> {
                        lockCompatibility(state.error ?: "与 Codex 的连接已断开")
                        statusMessage = state.error ?: "与 Codex 的连接已断开"
                        scheduleReconnect()
                    }
                    AppServerConnectionPhase.CONNECTING,
                    AppServerConnectionPhase.INITIALIZING -> lockCompatibility("正在核对 Codex 兼容性")
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
                val notificationThreadId = (notification.params as? JsonObject)?.string("threadId")
                val activeThreadId = detail.thread?.threadId ?: activeRepositoryThreadId()
                val buffered = snapshotBuffer.offer(notification)
                if (!buffered) detail = AppServerThreadReducer.apply(detail, notification)
                if (notificationThreadId == null || notificationThreadId == activeThreadId) {
                    runtimeSettings = AppServerRuntimeMapper.applyNotification(runtimeSettings, notification)
                    if (notification.method == "model/rerouted") {
                        runtimeSettings.model?.let { actualModel ->
                            selectedModelId = actualModel
                            runtimeCatalog = runtimeCatalog.copy(
                                models = runtimeCatalog.models.withSelectedModelFallback(),
                            )
                            selectedEffort = selectedModel?.supportedReasoningEfforts
                                ?.firstOrNull { it.reasoningEffort == selectedEffort }
                                ?.reasoningEffort
                                ?: selectedModel?.defaultReasoningEffort
                        }
                    }
                }
                if (!buffered && notificationThreadId == detail.thread?.threadId) {
                    if (notification.method == "turn/started") {
                        currentTurnId = (notification.params as? JsonObject)?.get("turn")?.jsonObject?.string("id")
                    }
                    if (notification.method == "turn/completed") currentTurnId = null
                }
                if (!buffered && notificationThreadId == detail.thread?.threadId) scheduleCache()
            }
        }
        viewModelScope.launch {
            client.serverRequests.collect { request ->
                if (!snapshotBuffer.offer(request)) applyServerRequest(request)
            }
        }
        viewModelScope.launch {
            val repo = repository
            val credentials = activeCredentials()
            connection = credentials
            if (repo != null && credentials != null) {
                workUiStore.bindRepositoryConnection(repo.id, credentials.connectionId)
            }
            val threadId = repo?.let { current ->
                credentials?.let { current.threadIdFor(it.connectionId) }
            }
            if (repo != null && threadId != null && credentials != null) {
                catalogRepository.loadDirectThread(directCacheMachineId(credentials.connectionId), threadId)?.let { cached ->
                    detail = cached.copy(cwd = cached.cwd ?: repo.path, approvals = emptyList())
                    currentTurnId = AppServerThreadReducer.activeTurnId(detail)
                    statusMessage = "已载入本机历史，正在连接 Codex…"
                }
            }
            connect()
        }
    }

    fun connect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        lockCompatibility("正在核对 Codex 兼容性")
        launchConnect()
    }

    private fun launchConnect() {
        if (client.state.value.phase == AppServerConnectionPhase.CONNECTING ||
            client.state.value.phase == AppServerConnectionPhase.INITIALIZING
        ) return
        viewModelScope.launch {
            val credentials = activeCredentials()
            if (credentials == null) {
                statusMessage = "请先在设置的 Work 卡片中配置开发机连接"
                return@launch
            }
            repository?.let { workUiStore.bindRepositoryConnection(it.id, credentials.connectionId) }
            connection = credentials
            if (client.state.value.phase == AppServerConnectionPhase.READY &&
                connectedConnectionId == credentials.connectionId
            ) {
                connected = true
                statusMessage = null
                finishCompatibilityGate(credentials)
                return@launch
            }
            statusMessage = "正在连接 Codex…"
            runCatching {
                if (client.state.value.phase == AppServerConnectionPhase.READY) client.disconnect()
                client.connect(
                    AppServerEndpoint(
                        webSocketUrl = credentials.appServerUrl,
                        bearerToken = credentials.appServerToken,
                        allowInsecureLoopback = credentials.appServerUrl.startsWith("ws://127.0.0.1") ||
                            credentials.appServerUrl.startsWith("ws://localhost"),
                    )
                )
                connectedConnectionId = credentials.connectionId
                connected = true
                statusMessage = null
                finishCompatibilityGate(credentials)
            }.onFailure {
                connected = false
                statusMessage = it.message ?: "无法连接 Codex"
            }
        }
    }

    private fun scheduleReconnect() {
        if (connection == null || reconnectJob?.isActive == true) return
        val attempt = reconnectAttempt++
        reconnectJob = viewModelScope.launch {
            val waitMs = reconnectPolicy.delayMs(attempt, Random.nextDouble())
            statusMessage = "连接已断开，${(waitMs + 999) / 1000} 秒后重试"
            delay(waitMs)
            reconnectJob = null
            launchConnect()
        }
    }

    private suspend fun activeCredentials(): WorkConnectionCredentials? = connectionStore.load().let { stored ->
        stored.connections.firstOrNull { it.connectionId == stored.activeConnectionId }
    }

    private suspend fun finishCompatibilityGate(credentials: WorkConnectionCredentials) {
        lockCompatibility("正在核对 Codex 兼容性")
        workUiStore.bindRepositoryConnection(repositoryId, credentials.connectionId)
        val threadId = workUiStore.state.value.repositories
            .firstOrNull { it.id == repositoryId }
            ?.threadIdFor(credentials.connectionId)
        val fixturePassed = threadId?.let { readThreadSnapshot(it) } ?: true
        if (!fixturePassed) {
            compatibility = AppServerCompatibility(
                AppServerCompatibilityLevel.READ_ONLY,
                "当前 Thread 无法通过 thread/read 兼容性检查",
            )
            statusMessage = compatibility.reason
            refreshCatalog()
            return
        }
        refreshCompatibility(credentials)
        if (writable && threadId != null) {
            runCatching {
                val lease = requireNotNull(acquireWriteLease())
                requireWriteLease(lease)
                client.request(
                    "thread/resume",
                    buildJsonObject { put("threadId", threadId) },
                    expectedConnectionGeneration = lease.connectionGeneration,
                ) { requireWriteLease(lease) }
            }.onFailure { error ->
                compatibility = AppServerCompatibility(
                    AppServerCompatibilityLevel.READ_ONLY,
                    "当前 Thread 恢复失败：${error.message ?: "未知错误"}",
                )
                statusMessage = compatibility.reason
            }
        }
        refreshCatalog()
    }

    private fun lockCompatibility(reason: String) {
        writeGeneration += 1
        compatibility = AppServerCompatibility(AppServerCompatibilityLevel.READ_ONLY, reason)
        pendingRequests.clear()
        if (detail.approvals.isNotEmpty()) detail = detail.copy(approvals = emptyList())
    }

    private fun acquireWriteLease(): DirectWorkWriteLease? = writeGeneration
        .takeIf { writable }
        ?.let { DirectWorkWriteLease(it, client.connectionGeneration) }

    private fun requireWriteLease(lease: DirectWorkWriteLease) {
        check(directWorkLeaseValid(writable, writeGeneration, client.connectionGeneration, lease)) {
            "连接状态已变化，请重新操作"
        }
    }

    private fun isCurrentGeneration(lease: DirectWorkWriteLease): Boolean =
        lease.compatibilityGeneration == writeGeneration &&
            lease.connectionGeneration == client.connectionGeneration

    private fun activeRepositoryThreadId(): String? {
        val connectionId = connection?.connectionId ?: return repository?.currentThreadId
        return repository?.threadIdFor(connectionId)
    }

    private fun applyServerRequest(request: AppServerRequest, cache: Boolean = true) {
        val params = request.params as? JsonObject ?: JsonObject(emptyMap())
        if (params.string("threadId") != detail.thread?.threadId) return
        val id = request.id.toString().trim('"')
        val requestedItemId = params.string("itemId")
        val displayParams = if (
            request.method == "item/tool/requestUserInput" &&
            !AppServerThreadReducer.containsItem(detail, requestedItemId)
        ) buildJsonObject {
            params.forEach { (key, value) -> put(key, value) }
            put("itemId", "request-$id")
        } else params
        val displayRequest = request.copy(params = displayParams)
        pendingRequests[id] = PendingServerRequest(
            request,
            DirectWorkWriteLease(writeGeneration, client.connectionGeneration),
        )
        detail = AppServerThreadReducer.applyServerRequest(detail, displayRequest)
        detail = detail.copy(
            approvals = detail.approvals + CodexApproval(
                approvalId = id,
                kind = if (request.method == "item/tool/requestUserInput") "user_input" else "approval",
                summary = params.string("reason")
                    ?: params.string("command")
                    ?: params.string("tool")
                    ?: "Codex 请求你的确认",
                createdAt = System.currentTimeMillis(),
                payload = displayParams,
            )
        )
        if (cache) scheduleCache()
    }

    fun send() {
        if (!canSend) return
        val lease = acquireWriteLease() ?: return
        val contents = inputState.getContents()
        viewModelScope.launch {
            sending = true
            statusMessage = null
            runCatching {
                val threadId = detail.thread?.threadId ?: startThread(lease)
                val input = buildInput(contents, lease)
                requireWriteLease(lease)
                val steering = isRunning
                val response = client.request(
                    if (steering) "turn/steer" else "turn/start",
                    if (steering) buildJsonObject {
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
                    expectedConnectionGeneration = lease.connectionGeneration,
                    beforeAttempt = { requireWriteLease(lease) },
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
                    scheduleCache()
                }
                inputState.clearInput()
                persistDraft("")
                acceptedPreferences = currentPreferences()
            }.onFailure { error ->
                statusMessage = if (rollbackRejectedPreferences(error)) {
                    "Codex 拒绝了当前运行参数，已恢复上次可用设置"
                } else error.message ?: "无法发送"
            }
            sending = false
        }
    }

    fun interrupt() {
        val lease = acquireWriteLease() ?: return
        val threadId = detail.thread?.threadId ?: return
        val turnId = currentTurnId ?: return
        viewModelScope.launch {
            runCatching {
                requireWriteLease(lease)
                client.request(
                    "turn/interrupt",
                    buildJsonObject { put("threadId", threadId); put("turnId", turnId) },
                    expectedConnectionGeneration = lease.connectionGeneration,
                ) { requireWriteLease(lease) }
            }.onFailure { statusMessage = it.message ?: "无法停止" }
        }
    }

    fun newThread() {
        val repo = repository ?: return
        if (isRunning) {
            statusMessage = "请先停止当前任务，再新建 Work 对话"
            return
        }
        if (!writable) {
            statusMessage = compatibility.reason ?: "连接并通过兼容性检查后才能新建对话"
            return
        }
        val lease = acquireWriteLease() ?: return
        detail = CodexThreadDetail(null, emptyList())
        currentTurnId = null
        runtimeSettings = runtimeSettings.copy(
            usedTokens = null,
            contextWindow = null,
            updatedAt = System.currentTimeMillis(),
        )
        inputState.clearInput()
        persistDraft("")
        sending = true
        statusMessage = "正在新建 Work 对话…"
        viewModelScope.launch {
            runCatching {
                requireWriteLease(lease)
                connection?.let { workUiStore.updateDirectThread(repo.id, it.connectionId, null) }
                startThread(lease)
            }
                .onSuccess {
                    acceptedPreferences = currentPreferences()
                    statusMessage = null
                }
                .onFailure { error ->
                    statusMessage = if (rollbackRejectedPreferences(error)) {
                        "Codex 拒绝了当前运行参数，已恢复上次可用设置"
                    } else error.message ?: "无法新建 Work 对话"
                }
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
        val lease = acquireWriteLease()
        if (lease == null) {
            statusMessage = compatibility.reason ?: "当前连接不可写"
            return
        }
        viewModelScope.launch {
            statusMessage = "正在压缩上下文…"
            runCatching {
                requireWriteLease(lease)
                client.request(
                    "thread/compact/start",
                    buildJsonObject { put("threadId", threadId) },
                    expectedConnectionGeneration = lease.connectionGeneration,
                ) { requireWriteLease(lease) }
            }.onSuccess {
                statusMessage = "上下文压缩已开始"
            }.onFailure {
                statusMessage = it.message ?: "无法压缩上下文"
            }
        }
    }

    fun resolveApproval(approvalId: String, decision: String) {
        val lease = acquireWriteLease() ?: return
        val pending = pendingRequests[approvalId] ?: return
        if (pending.lease != lease) return
        requireWriteLease(lease)
        pendingRequests.remove(approvalId)
        client.respond(
            pending.request,
            buildJsonObject { put("decision", decision) },
            expectedConnectionGeneration = lease.connectionGeneration,
        )
        detail = detail.copy(approvals = detail.approvals.filterNot { it.approvalId == approvalId })
        scheduleCache()
    }

    fun resolveInteraction(approvalId: String, answer: String) {
        val lease = acquireWriteLease() ?: return
        val pending = pendingRequests[approvalId] ?: return
        if (pending.lease != lease) return
        val incoming = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(answer).jsonObject }
            .getOrNull()
        val incomingAnswers = incoming?.get("answers") as? JsonObject ?: JsonObject(emptyMap())
        requireWriteLease(lease)
        pendingRequests.remove(approvalId)
        client.respond(pending.request, buildJsonObject {
            put("answers", buildJsonObject {
                incomingAnswers.forEach { (questionId, value) ->
                    val values = when (value) {
                        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        is JsonPrimitive -> value.contentOrNull?.let(::listOf).orEmpty()
                        is JsonObject -> (value["answers"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            .orEmpty()
                    }
                    put(questionId, buildJsonObject {
                        put("answers", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            })
        }, expectedConnectionGeneration = lease.connectionGeneration)
        detail = detail.copy(approvals = detail.approvals.filterNot { it.approvalId == approvalId })
        scheduleCache()
    }

    fun cancelInteraction(approvalId: String) {
        val lease = acquireWriteLease() ?: return
        val pending = pendingRequests[approvalId] ?: return
        if (pending.lease != lease) return
        requireWriteLease(lease)
        pendingRequests.remove(approvalId)
        client.respondError(
            pending.request,
            -32800,
            "User cancelled",
            expectedConnectionGeneration = lease.connectionGeneration,
        )
        detail = detail.copy(approvals = detail.approvals.filterNot { it.approvalId == approvalId })
        scheduleCache()
    }

    private suspend fun startThread(lease: DirectWorkWriteLease): String {
        val repo = requireNotNull(repository)
        requireWriteLease(lease)
        val result = client.request(
            "thread/start",
            buildJsonObject {
                put("cwd", repo.path)
                selectedModelId?.let { put("model", it) }
                put("serviceTier", if (fastMode) JsonPrimitive("priority") else kotlinx.serialization.json.JsonNull)
                put("approvalPolicy", approvalPolicy())
                put("sandbox", sandboxMode())
            },
            expectedConnectionGeneration = lease.connectionGeneration,
        ) { requireWriteLease(lease) }
        detail = AppServerThreadReducer.snapshot(result, repo.id, cacheMachineId())
        val threadId = requireNotNull(detail.thread?.threadId)
        requireWriteLease(lease)
        workUiStore.updateDirectThread(repo.id, requireNotNull(connection).connectionId, threadId)
        persistCacheNow()
        check(readThreadSnapshot(threadId)) { "新 Thread 无法通过 thread/read 校验" }
        return threadId
    }

    private suspend fun readThreadSnapshot(threadId: String): Boolean {
        val repo = repository ?: return false
        val snapshotGeneration = DirectWorkWriteLease(writeGeneration, client.connectionGeneration)
        snapshotBuffer.begin(threadId)
        return try {
            val snapshot = client.request("thread/read", buildJsonObject {
                put("threadId", threadId)
                put("includeTurns", true)
            })
            val mapped = AppServerThreadReducer.snapshot(snapshot, repo.id, cacheMachineId())
            val replay = snapshotBuffer.complete(mapped)
            if (!isCurrentGeneration(snapshotGeneration)) return false
            detail = replay.detail
            replay.serverRequests.forEach { applyServerRequest(it, cache = false) }
            currentTurnId = AppServerThreadReducer.activeTurnId(detail)
            persistCacheNow()
            true
        } catch (error: Throwable) {
            val replay = snapshotBuffer.abort(detail)
            if (isCurrentGeneration(snapshotGeneration)) {
                detail = replay.detail
                replay.serverRequests.forEach { applyServerRequest(it, cache = false) }
                currentTurnId = AppServerThreadReducer.activeTurnId(detail)
                persistCacheNow()
                statusMessage = "历史同步失败：${error.message ?: "未知错误"}"
            }
            false
        }
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

    private suspend fun refreshCompatibility(credentials: WorkConnectionCredentials) {
        val direct = AppServerCompatibilityGate.evaluateInitialize(client.state.value.serverInfo)
        if (credentials.supervisorUrl.isNullOrBlank() || credentials.supervisorToken.isNullOrBlank()) {
            compatibility = direct
            statusMessage = compatibility.reason
            return
        }
        if (direct.level != AppServerCompatibilityLevel.TEXT_ONLY) {
            compatibility = direct
            statusMessage = compatibility.reason
            return
        }
        compatibility = runCatching {
            AppServerCompatibilityGate.evaluateWithSupervisor(
                direct,
                attachmentClient.runtimeFacts(credentials),
            )
        }.getOrElse { error ->
            if (direct.level == AppServerCompatibilityLevel.TEXT_ONLY) direct.copy(
                reason = "${direct.reason}；Supervisor 探针失败：${error.message ?: "未知错误"}",
            ) else direct
        }
        compatibility.reason?.let { reason ->
            if (compatibility.level != AppServerCompatibilityLevel.FULL) statusMessage = reason
        }
    }

    private suspend fun buildInput(
        contents: List<UIMessagePart>,
        lease: DirectWorkWriteLease,
    ): JsonArray {
        val connection = requireNotNull(connection)
        val uploadedDocuments = contents.filterIsInstance<UIMessagePart.Document>().associateWith { part ->
            requireWriteLease(lease)
            attachmentClient.upload(connection, part.url.toUri(), part.fileName, part.mime)
        }
        val uploadedImages = contents.filterIsInstance<UIMessagePart.Image>().associateWith { part ->
            requireWriteLease(lease)
            attachmentClient.upload(connection, part.url.toUri(), "image.jpg", "image/jpeg")
        }
        requireWriteLease(lease)
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
        scheduleCache()
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
                    is UIMessagePart.Document -> {
                        val receipt = requireNotNull(uploadedDocuments[part])
                        add(appServerDocumentMention(receipt.fileName, receipt.localPath))
                    }
                    else -> Unit
                }
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

    private fun currentPreferences(): WorkRepositoryPreferences {
        val repo = repository ?: return WorkRepositoryPreferences()
        return repo.preferences.copy(
            model = selectedModelId,
            permission = selectedPermission,
            fastMode = fastMode,
        ).withEffort(selectedModelId, selectedEffort)
    }

    private fun persistPreferences(preferences: WorkRepositoryPreferences = currentPreferences()) {
        val repo = repository ?: return
        viewModelScope.launch {
            workUiStore.updateRepositorySession(
                repo.id,
                preferences = preferences,
            )
        }
    }

    private fun rollbackRejectedPreferences(error: Throwable): Boolean {
        val current = currentPreferences()
        val restored = AppServerOptionRollback.rollback(current, acceptedPreferences, error)
        if (restored == current) return false
        applyPreferences(restored)
        persistPreferences(restored)
        return true
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

    private fun scheduleCache() {
        if (detail.thread == null) return
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            delay(150)
            catalogRepository.cacheDirectThread(detail)
        }
    }

    private suspend fun persistCacheNow() {
        cacheJob?.cancel()
        cacheJob = null
        catalogRepository.cacheDirectThread(detail)
    }

    private fun cacheMachineId(): String = directCacheMachineId(requireNotNull(connection).connectionId)

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

private data class PendingServerRequest(
    val request: AppServerRequest,
    val lease: DirectWorkWriteLease,
)

internal data class DirectWorkWriteLease(
    val compatibilityGeneration: Long,
    val connectionGeneration: Long,
)

internal fun directWorkLeaseValid(
    writable: Boolean,
    currentCompatibilityGeneration: Long,
    currentConnectionGeneration: Long,
    lease: DirectWorkWriteLease,
): Boolean = writable &&
    currentCompatibilityGeneration == lease.compatibilityGeneration &&
    currentConnectionGeneration == lease.connectionGeneration

internal fun directCacheMachineId(connectionId: String): String = "direct:$connectionId"

internal fun directWorkWritable(connected: Boolean, level: AppServerCompatibilityLevel): Boolean =
    connected && level in setOf(AppServerCompatibilityLevel.FULL, AppServerCompatibilityLevel.TEXT_ONLY)

internal fun appServerDocumentMention(name: String, path: String): JsonObject = buildJsonObject {
    put("type", "mention")
    put("name", name)
    put("path", path)
}

internal fun directWorkInputRestriction(
    contents: List<UIMessagePart>,
    modelInputModalities: List<String>,
    compatibilityLevel: AppServerCompatibilityLevel,
): String? {
    if (contents.any { it is UIMessagePart.Image } && "image" !in modelInputModalities) {
        return "当前模型不支持图片，请切换支持视觉输入的模型"
    }
    if (compatibilityLevel != AppServerCompatibilityLevel.FULL && contents.any {
            it is UIMessagePart.Image || it is UIMessagePart.Document
        }
    ) {
        return "当前连接未启用受控附件上传，请配置 Supervisor 或移除附件"
    }
    return null
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
