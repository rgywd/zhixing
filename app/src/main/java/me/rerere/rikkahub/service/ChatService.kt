package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.AUTO_COMPACT_RECENT_TOKEN_BUDGET
import me.rerere.rikkahub.data.ai.AUTO_COMPACT_SUMMARY_TOKENS
import me.rerere.rikkahub.data.ai.ContextCompactionTrigger
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.RuntimeContextStore
import me.rerere.rikkahub.data.ai.MonthlySpendingAttachmentCleanupCandidate
import me.rerere.rikkahub.data.ai.MonthlySpendingToolCallRef
import me.rerere.rikkahub.data.ai.PromptCompactionResult
import me.rerere.rikkahub.data.ai.applyContextCheckpoint
import me.rerere.rikkahub.data.ai.activateLatestPreparedContextCheckpoint
import me.rerere.rikkahub.data.ai.automaticRecentTokenBudget
import me.rerere.rikkahub.data.ai.bindMonthlySpendingSaveSourceMessages
import me.rerere.rikkahub.data.ai.buildContextCompactionPlan
import me.rerere.rikkahub.data.ai.clearContextCheckpoints
import me.rerere.rikkahub.data.ai.contextCompactionPolicy
import me.rerere.rikkahub.data.ai.containsMonthlySpendingAttachmentUris
import me.rerere.rikkahub.data.ai.estimatePromptTokens
import me.rerere.rikkahub.data.ai.findReadyMonthlySpendingAttachmentCleanupCandidates
import me.rerere.rikkahub.data.ai.hasPreparedContextCheckpoint
import me.rerere.rikkahub.data.ai.isReadyForMonthlySpendingAttachmentCleanup
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.redactMonthlySpendingAttachments
import me.rerere.rikkahub.data.ai.renderMessagesForCompaction
import me.rerere.rikkahub.data.ai.projectContextForPrompt
import me.rerere.rikkahub.data.ai.splitCompactionContent
import me.rerere.rikkahub.data.ai.successfulMonthlySpendingSaveToolCalls
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.resolveHistoricalMemorySource
import me.rerere.rikkahub.data.ai.tools.createAssistantUserPromptTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.createKnowledgeTools
import me.rerere.rikkahub.data.ai.UserPromptResolver
import me.rerere.rikkahub.data.knowledge.KnowledgeSpaceService
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.github.GitHubCliRunner
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryDocumentRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.task.AssistantTaskRepository
import me.rerere.rikkahub.data.task.assistantTaskSourceAnchorFor
import me.rerere.rikkahub.data.task.AssistantTaskStep
import me.rerere.rikkahub.data.task.applyAssistantTaskResultPresentation
import me.rerere.rikkahub.data.task.extractAssistantTaskFailure
import me.rerere.rikkahub.data.task.naturalizeAssistantTaskTitle
import me.rerere.rikkahub.data.task.progressText
import me.rerere.rikkahub.data.task.requiresDurableTask
import me.rerere.rikkahub.data.task.requiresVisibleTask
import me.rerere.rikkahub.data.task.extractAssistantTaskResultLinks
import me.rerere.rikkahub.data.task.extractAssistantTaskResultPresentation
import me.rerere.rikkahub.data.task.successfulAgendaPresentationToolCallIds
import me.rerere.rikkahub.data.workspace.WorkspaceVariableStore
import me.rerere.rikkahub.data.workspace.parseWorkspaceVariableDeclarations
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.MEDIUM,
    maxTokens: Int? = null,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    maxTokens = maxTokens,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val generationForegroundController: ChatGenerationForegroundController,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryDocumentRepository: MemoryDocumentRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val githubCliRunner: GitHubCliRunner,
    private val workspaceVariableStore: WorkspaceVariableStore,
    private val knowledgeSpaceService: KnowledgeSpaceService,
    private val folderRepository: FolderRepository,
    private val assistantTaskRepository: AssistantTaskRepository,
    private val runtimeContextStore: RuntimeContextStore,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val userPromptResolver = UserPromptResolver(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend () -> Unit
    ): Job = appScope.launch(start = start) {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    private fun launchGeneration(
        conversationId: Uuid,
        protectInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        val generationId = Uuid.random()
        if (protectInBackground) {
            generationForegroundController.acquire(generationId, conversationId)
        }
        return appScope.launch {
            try {
                block()
            } finally {
                if (protectInBackground) {
                    generationForegroundController.release(generationId)
                }
            }
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            // 生成中保留内存态(含流式回复), 避免被数据库旧内容覆盖导致切回时 UI 空白
            if (!session.isGenerating) {
                updateConversation(conversationId, conversation)
            }
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        runtimeContext: UIMessageAnnotation.RuntimeContext? = null,
    ) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = launchGeneration(
            conversationId = conversationId,
            protectInBackground = answer,
        ) {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(
                    parts = content,
                    assistant = assistant,
                    conversationId = conversationId,
                )

                // 添加消息到列表
                val userMessage = UIMessage(
                    role = MessageRole.USER,
                    parts = processedContent,
                )
                val attachedRuntimeContext = runtimeContext?.let { envelope ->
                    runtimeContextStore.attach(
                        context = envelope,
                        conversationId = conversationId.toString(),
                        messageId = userMessage.id.toString(),
                    )
                }
                val userPromptSnapshot = userPromptResolver.snapshotForFirstUserMessage(
                    conversation = currentConversation,
                    assistant = assistant,
                )
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + userMessage.copy(
                        annotations = attachedRuntimeContext?.let(::listOf).orEmpty(),
                    ).toMessageNode(),
                    userPromptSnapshot = userPromptSnapshot,
                )
                try {
                    saveConversation(conversationId, newConversation)
                } catch (error: Throwable) {
                    attachedRuntimeContext?.contextId?.let { contextId ->
                        runCatching { runtimeContextStore.delete(contextId) }
                    }
                    throw error
                }

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    private suspend fun preprocessUserInputParts(
        parts: List<UIMessagePart>,
        assistant: Assistant,
        conversationId: Uuid,
    ): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    val variableResult = if (assistant.workspaceId != null) {
                        parseWorkspaceVariableDeclarations(part.text).also { result ->
                            if (result.declarations.isNotEmpty()) {
                                workspaceVariableStore.apply(conversationId, result.declarations)
                            }
                        }
                    } else {
                        null
                    }
                    part.copy(
                        text = (variableResult?.safeText ?: part.text).replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = launchGeneration(
            conversationId = conversationId,
            protectInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                runCatching { previousJob?.join() }
                finishInterruptedPendingTools(conversationId)
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    conversation.assistantTaskSourceAnchorFor(message)?.let { source ->
                        runCatching {
                            assistantTaskRepository.retryForSource(
                                conversationId = conversationId.toString(),
                                anchorMessageId = source.messageId,
                                anchorNodeId = source.nodeId,
                            )
                        }.onFailure { Log.w(TAG, "Unable to resume assistant task retry", it) }
                    }
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        conversation.assistantTaskSourceAnchorFor(message)?.let { source ->
                            runCatching {
                                assistantTaskRepository.retryForSource(
                                    conversationId = conversationId.toString(),
                                    anchorMessageId = source.messageId,
                                    anchorNodeId = source.nodeId,
                                )
                            }.onFailure { Log.w(TAG, "Unable to resume assistant task retry", it) }
                        }
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()

        val job = launchGeneration(conversationId) {
            try {
                runCatching { previousJob?.join() }
                val conversation = session.state.value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(approvalState = newApprovalState)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                if (answer != null) {
                    assistantTaskRepository.findActiveForConversation(conversationId.toString())
                        ?.takeIf { it.status == "WAITING_FOR_INPUT" }
                        ?.let { assistantTaskRepository.resume(it.id) }
                }

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        var initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        if (initialConversation.userPromptSnapshot == null) {
            initialConversation = initialConversation.copy(
                userPromptSnapshot = userPromptResolver.snapshotForFirstUserMessage(
                    conversation = initialConversation,
                    assistant = assistant,
                )
            )
            saveConversation(conversationId, initialConversation)
        }
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        var successfulSaveToolCallsBeforeGeneration = emptySet<MonthlySpendingToolCallRef>()
        var successfulAgendaToolCallIdsBeforeGeneration = emptySet<String>()
        var promptTokenEstimateForCompaction: Int? = null
        var promptCompactedThisTurn = false
        var assistantTaskId = runCatching {
            assistantTaskRepository.findActiveForConversation(conversationId.toString())?.id
        }.onFailure {
            Log.w(TAG, "Unable to read active assistant task", it)
        }.getOrNull()

        suspend fun trackTaskStep(step: AssistantTaskStep) {
            if (!step.requiresVisibleTask(me.rerere.rikkahub.utils.JsonInstant)) return
            if (assistantTaskId == null) {
                val sourceMessage = initialConversation.currentMessages
                    .lastOrNull { it.role == MessageRole.USER }
                val sourceNode = sourceMessage?.let(initialConversation::getMessageNodeByMessage)
                val created = runCatching {
                    assistantTaskRepository.create(
                        title = naturalizeAssistantTaskTitle(sourceMessage?.toText().orEmpty()),
                        conversationId = conversationId.toString(),
                        anchorMessageId = sourceMessage?.id?.toString(),
                        anchorNodeId = sourceNode?.id?.toString(),
                    )
                }.getOrElse { error ->
                    if (step.requiresDurableTask(me.rerere.rikkahub.utils.JsonInstant)) {
                        throw IllegalStateException(
                            "Unable to persist a traceable task before an external action",
                            error,
                        )
                    }
                    Log.w(TAG, "Read-only multi-step task tracking unavailable", error)
                    return
                }
                assistantTaskId = created.id
            }
            val taskId = checkNotNull(assistantTaskId)
            if (step.requiresUserAnswer && !step.hasUserAnswer) {
                assistantTaskRepository.waitForInput(taskId, step.progressText())
            } else {
                assistantTaskRepository.recordProgress(
                    taskId = taskId,
                    message = step.progressText(),
                    idempotencyKey = "tool:${step.toolCallId}:start",
                )
            }
        }

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            successfulSaveToolCallsBeforeGeneration =
                conversation.successfulMonthlySpendingSaveToolCalls()
            successfulAgendaToolCallIdsBeforeGeneration = successfulAgendaPresentationToolCallIds(
                messages = conversation.currentMessages,
                json = me.rerere.rikkahub.utils.JsonInstant,
            )

            // start generating
            val session = getOrCreateSession(conversationId)
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationUserPromptSnapshot = conversation.userPromptSnapshot,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                memoryDocuments = memoryDocumentRepository.getPromptDocuments(
                    contextScopeId = if (assistant.useGlobalMemory) {
                        MemoryDocumentRepository.GLOBAL_SCOPE_ID
                    } else {
                        assistant.id.toString()
                    }
                ),
                memoryConversationId = conversationId.toString(),
                historicalMemorySourceResolver = if (assistant.enableRecentChatsReference) {
                    { sourceRef, quote ->
                        resolveHistoricalMemorySource(
                            conversationRepository = conversationRepo,
                            assistantId = assistant.id,
                            currentConversationId = conversationId,
                            encodedSourceRef = sourceRef,
                            quote = quote,
                        )
                    }
                } else {
                    null
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (assistant.enableWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(
                        localTools.getTools(
                            options = assistant.localTools,
                            conversationId = conversationId.toString(),
                        )
                    )
                    if (assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id, conversationId))
                    }
                    addAll(createKnowledgeTools(assistant.workspaceId?.toString(), workspaceRepository, knowledgeSpaceService))
                    addAll(createAssistantUserPromptTools(assistant, workspaceRepository))
                    addAll(
                        createWorkspaceToolsIfReady(
                            conversationId = conversationId,
                            workspaceId = assistant.workspaceId?.toString(),
                            cwd = conversation.workspaceCwd,
                        )
                    )
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        add(
                            Tool(
                                name = "mcp__${serverName}__${tool.name}",
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                },
                onPromptPrepared = { estimatedTokens, generationMessages ->
                    promptTokenEstimateForCompaction = estimatedTokens
                    if (messageRange != null) return@generateText null

                    val policy = contextCompactionPolicy(model.contextWindowTokens)
                    var latestMessages = generationMessages
                    if (
                        policy.requiresSynchronousFallback(estimatedTokens) &&
                        !latestMessages.hasPreparedContextCheckpoint()
                    ) {
                        session.getCompactionJob()?.join()
                        latestMessages = getConversationFlow(conversationId).value.currentMessages
                    }

                    val compactedMessages = when {
                        policy.shouldActivate(estimatedTokens) &&
                            latestMessages.hasPreparedContextCheckpoint() -> {
                            activatePreparedContextCheckpoint(conversationId, latestMessages)
                        }

                        policy.requiresSynchronousFallback(estimatedTokens) -> {
                            Log.w(
                                TAG,
                                "No prepared checkpoint at safety ceiling; compacting synchronously " +
                                    "without foreground UI: estimatedTokens=$estimatedTokens"
                            )
                            createContextCheckpoint(
                                conversationId = conversationId,
                                messages = latestMessages,
                                additionalPrompt = "",
                                targetTokens = AUTO_COMPACT_SUMMARY_TOKENS,
                                recentTokenBudget = automaticRecentTokenBudget(
                                    contextWindowTokens = model.contextWindowTokens,
                                    sourcePromptTokens = estimatedTokens,
                                    projectedHistoryTokens = estimatePromptTokens(
                                        latestMessages.projectContextForPrompt().messages,
                                        emptyList(),
                                    ),
                                ),
                                trigger = ContextCompactionTrigger.AUTO,
                                sourceTokenEstimate = estimatedTokens,
                                forceCompaction = false,
                                active = true,
                            )
                        }

                        else -> null
                    }
                    compactedMessages?.let { messages ->
                        promptCompactedThisTurn = true
                        PromptCompactionResult(
                            messages = messages,
                            maximumPromptTokens = policy.maximumPromptTokens,
                        )
                    }
                },
                onTaskStep = ::trackTaskStep,
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                withContext(NonCancellable) {
                    val currentConversation = getConversationFlow(conversationId).value
                    val updatedConversation = currentConversation.copy(
                        messageNodes = currentConversation.messageNodes.map { node ->
                            node.copy(messages = node.messages.map { it.finishReasoning() })
                        },
                        updateAt = Instant.now()
                    ).bindMonthlySpendingSaveSourceMessages()
                    updateConversation(conversationId, updatedConversation)
                    runCatching {
                        saveConversation(conversationId, updatedConversation)
                    }.onFailure {
                        Log.e(TAG, "Unable to persist partial generation for $conversationId", it)
                    }

                    // 生成结束：取消 Live Update 通知，后台时发送完成通知
                    appEventBus.tryEmit(
                        AppEvent.ChatGenerationEnded(
                            conversationId = conversationId,
                            senderName = senderName,
                            contentPreview = updatedConversation.currentMessages.lastOrNull()
                                ?.toText()?.take(50)?.trim() ?: "",
                        )
                    )
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                            .bindMonthlySpendingSaveSourceMessages()
                        updateConversation(conversationId, updatedConversation)

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
            if (it !is CancellationException) {
                assistantTaskId?.let { taskId ->
                    runCatching {
                        assistantTaskRepository.fail(
                            taskId = taskId,
                            errorCode = "GENERATION_FAILED",
                            message = "处理未完成，可以重试",
                        )
                    }.onFailure { taskError ->
                        Log.w(TAG, "Unable to mark assistant task failed", taskError)
                    }
                }
            }
        }.onSuccess {
            var finalConversation = getConversationFlow(conversationId).value
            val hasUnexecutedTools = finalConversation.currentMessages.any { message ->
                message.getTools().any { !it.isExecuted }
            }
            if (!hasUnexecutedTools) {
                val newlySuccessfulSaveToolCalls =
                    finalConversation.successfulMonthlySpendingSaveToolCalls() -
                        successfulSaveToolCallsBeforeGeneration
                finalConversation = cleanupReadyMonthlySpendingAttachments(
                    conversationId = conversationId,
                    newlySuccessfulToolCalls = newlySuccessfulSaveToolCalls,
                )
                val resultPresentation = extractAssistantTaskResultPresentation(
                    messages = finalConversation.currentMessages,
                    json = me.rerere.rikkahub.utils.JsonInstant,
                    previousSuccessfulAgendaToolCallIds = successfulAgendaToolCallIdsBeforeGeneration,
                )
                if (resultPresentation != null) {
                    finalConversation = finalConversation
                        .updateCurrentMessages(
                            applyAssistantTaskResultPresentation(
                                messages = finalConversation.currentMessages,
                                presentation = resultPresentation,
                            ),
                        )
                        .bindMonthlySpendingSaveSourceMessages()
                    updateConversation(conversationId, finalConversation)
                    saveConversation(conversationId, finalConversation)
                }

                if (!promptCompactedThisTurn) {
                    promptTokenEstimateForCompaction?.let { estimatedTokens ->
                        scheduleAutomaticContextCompaction(
                            conversationId = conversationId,
                            messages = finalConversation.currentMessages,
                            sourcePromptTokenEstimate = estimatedTokens,
                            contextWindowTokens = model.contextWindowTokens,
                        )
                    }
                }

                launchWithConversationReference(conversationId) {
                    generateTitle(conversationId, finalConversation)
                }
                launchWithConversationReference(conversationId) {
                    generateSuggestion(conversationId, finalConversation)
                }
                assistantTaskId?.let { taskId ->
                    runCatching {
                        val taskFailure = extractAssistantTaskFailure(
                            messages = finalConversation.currentMessages,
                            json = me.rerere.rikkahub.utils.JsonInstant,
                        )
                        if (taskFailure != null) {
                            if (taskFailure.resultMayBeUnknown) {
                                assistantTaskRepository.waitForInput(
                                    taskId = taskId,
                                    message = "外部操作结果待确认，请先查看目标系统再决定是否重试",
                                )
                            } else {
                                assistantTaskRepository.fail(
                                    taskId = taskId,
                                    errorCode = taskFailure.code,
                                    message = "有一步没有完成，可以调整后重试",
                                )
                            }
                            return@runCatching
                        }
                        val resultLinks = extractAssistantTaskResultLinks(
                            messages = finalConversation.currentMessages,
                            json = me.rerere.rikkahub.utils.JsonInstant,
                        )
                        resultLinks.forEach { link ->
                            assistantTaskRepository.addLink(
                                taskId = taskId,
                                objectType = link.objectType,
                                objectId = link.objectId,
                                role = link.role,
                            )
                        }
                        resultPresentation?.title?.let { title ->
                            assistantTaskRepository.updateTitle(taskId, title)
                        }
                        val primaryResult = resultLinks.firstOrNull()
                        assistantTaskRepository.complete(
                            taskId = taskId,
                            summary = finalConversation.currentMessages.lastOrNull()
                                ?.toText()
                                ?.lineSequence()
                                ?.firstOrNull(String::isNotBlank)
                                ?: "已完成",
                            resultKind = primaryResult?.objectType,
                            resultRef = primaryResult?.objectId,
                        )
                    }.onFailure { taskError ->
                        Log.w(TAG, "Unable to mark assistant task complete", taskError)
                    }
                }
            }
        }
    }

    private suspend fun cleanupReadyMonthlySpendingAttachments(
        conversationId: Uuid,
        newlySuccessfulToolCalls: Set<MonthlySpendingToolCallRef>,
    ): Conversation {
        var conversation = getConversationFlow(conversationId).value
        conversation
            .findReadyMonthlySpendingAttachmentCleanupCandidates(
                eligibleToolCalls = newlySuccessfulToolCalls,
            )
            .mapNotNull { candidate ->
                candidate.copy(
                    attachmentUris = candidate.attachmentUris
                        .filterTo(mutableSetOf()) { uri ->
                            filesManager.isManagedUploadFile(uri.toUri())
                        },
                ).takeIf { it.attachmentUris.isNotEmpty() }
            }
            .forEach { candidate ->
                conversation = cleanupSavedMonthlySpendingAttachments(
                    conversationId = conversationId,
                    candidate = candidate,
                )
            }
        return conversation
    }

    private suspend fun cleanupSavedMonthlySpendingAttachments(
        conversationId: Uuid,
        candidate: MonthlySpendingAttachmentCleanupCandidate,
    ): Conversation = withContext(NonCancellable) {
        val session = getOrCreateSession(conversationId)
        session.mutationMutex.withLock {
            val latestConversation = session.state.value
            if (!latestConversation.isReadyForMonthlySpendingAttachmentCleanup(candidate)) {
                return@withLock latestConversation
            }

            val updatedAt = Instant.now()
            val redactedConversation = latestConversation
                .redactMonthlySpendingAttachments(
                    redaction = candidate.redaction,
                    placeholder = context.getString(R.string.ledger_attachment_cleanup_placeholder),
                )
                .copy(updateAt = updatedAt)

            val persistedConversation = try {
                conversationRepo.replaceConversationMessageNodes(
                    conversationId = conversationId,
                    messageNodes = redactedConversation.messageNodes,
                    updatedAt = updatedAt,
                ) ?: return@withLock latestConversation
            } catch (error: Exception) {
                // Message-node replacement commits Room before refreshing FTS. A post-commit
                // indexing failure must not make us retain a stale in-memory attachment reference.
                val readBack = runCatching {
                    conversationRepo.getConversationById(conversationId)
                }.getOrNull()
                if (
                    readBack != null &&
                    !readBack.containsMonthlySpendingAttachmentUris(candidate.redaction)
                ) {
                    Log.w(
                        TAG,
                        "Monthly spending attachment redaction was committed despite a " +
                            "post-persistence failure (${error.javaClass.simpleName})",
                    )
                    readBack
                } else {
                    Log.e(
                        TAG,
                        "Monthly spending attachment redaction was not confirmed; cleanup skipped " +
                            "(${error.javaClass.simpleName})",
                    )
                    return@withLock latestConversation
                }
            }

            session.recordAttachmentRedaction(candidate.redaction)
            session.state.update { current ->
                current
                    .redactMonthlySpendingAttachments(
                        redaction = candidate.redaction,
                        placeholder = context.getString(R.string.ledger_attachment_cleanup_placeholder),
                    )
                    .copy(
                        updateAt = maxOf(current.updateAt, persistedConversation.updateAt),
                    )
            }

            val deletableUris = runCatching {
                findUnreferencedManagedUploadUris(candidate.attachmentUris)
            }.getOrElse { error ->
                Log.e(
                    TAG,
                    "Monthly spending attachment reference scan failed; physical files retained " +
                        "(${error.javaClass.simpleName})",
                )
                emptyList()
            }
            runCatching {
                filesManager.deleteManagedUploadFiles(deletableUris)
            }.onSuccess { result ->
                Log.i(
                    TAG,
                    "Monthly spending attachment cleanup: candidates=${candidate.attachmentUris.size}, " +
                        "requested=${result.requested}, " +
                        "removed=${result.removed}, rejected=${result.rejected}, " +
                        "failed=${result.failed}, " +
                        "retainedReferences=${candidate.attachmentUris.size - deletableUris.size}",
                )
            }.onFailure { error ->
                Log.e(
                    TAG,
                    "Monthly spending attachment file cleanup failed (${error.javaClass.simpleName})",
                )
            }

            runCatching {
                OcrTransformer.removeCachedResults(candidate.attachmentUris)
            }.onFailure { error ->
                Log.e(
                    TAG,
                    "Monthly spending OCR cache cleanup failed (${error.javaClass.simpleName})",
                )
            }

            session.state.value
        }
    }

    private suspend fun findUnreferencedManagedUploadUris(
        candidateUris: Set<String>,
    ): List<android.net.Uri> {
        val candidatePaths = candidateUris
            .mapNotNull { uri ->
                val parsed = uri.toUri()
                filesManager.managedUploadRelativePath(parsed)?.let { path -> path to parsed }
            }
            .toMap()
        if (candidatePaths.isEmpty()) return emptyList()

        val activeConversations = sessions.values.map { it.state.value }
        val persistedConversations = conversationRepo.getAllConversationsWithMessages()
        val referencedPaths = (persistedConversations.asSequence() + activeConversations.asSequence())
            .asSequence()
            .flatMap { it.files.asSequence() }
            .mapNotNull(filesManager::managedUploadRelativePath)
            .toSet()

        return candidatePaths
            .filterKeys { it !in referencedPaths }
            .values
            .toList()
    }

    private suspend fun createWorkspaceToolsIfReady(
        conversationId: Uuid,
        workspaceId: String?,
        cwd: String? = null,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            workspaceId = workspaceId,
            workspaceRepository = workspaceRepository,
            githubCliRunner = githubCliRunner,
            cwd = cwd,
            shellEnvironment = {
                workspaceVariableStore.environment(conversationId)
            },
        )
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved user-answer tool calls.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateWorkTitle(message: String): String? {
        if (message.isBlank()) return null

        return try {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.fastModelId) ?: return null
            val provider = model.findProvider(settings.providers) ?: return null
            val result = providerManager.getProviderByType(provider).generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to message.take(2_000),
                        ),
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )
            normalizeWorkSessionTitle(result.choices.firstOrNull()?.message?.toText().orEmpty())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Failed to generate Work session title", error)
            null
        }
    }

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val expectedLastMessageId = conversation.currentMessages.lastOrNull()?.id
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            val generatedTitle = result.choices[0].message?.toText()?.trim() ?: ""
            mutateAndSaveConversation(conversationId) { latestConversation ->
                if (
                    expectedLastMessageId != null &&
                    latestConversation.currentMessages.lastOrNull()?.id != expectedLastMessageId
                ) {
                    null
                } else if (!force && latestConversation.title.isNotBlank()) {
                    null
                } else {
                    latestConversation.copy(title = generatedTitle)
                }
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val expectedLastMessageId = conversation.currentMessages.lastOrNull()?.id
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            mutateAndSaveConversation(conversationId) { latestConversation ->
                if (
                    expectedLastMessageId != null &&
                    latestConversation.currentMessages.lastOrNull()?.id != expectedLastMessageId
                ) {
                    null
                } else {
                    latestConversation.copy(
                        chatSuggestions = suggestions.take(10),
                    )
                }
            }
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    private fun scheduleAutomaticContextCompaction(
        conversationId: Uuid,
        messages: List<UIMessage>,
        sourcePromptTokenEstimate: Int,
        contextWindowTokens: Int,
    ) {
        val policy = contextCompactionPolicy(contextWindowTokens)
        if (!policy.shouldPrepare(sourcePromptTokenEstimate)) return
        if (messages.hasPreparedContextCheckpoint()) return

        val session = getOrCreateSession(conversationId)
        val projectedHistoryTokens = estimatePromptTokens(
            messages.projectContextForPrompt().messages,
            emptyList(),
        )
        val job = launchWithConversationReference(
            conversationId = conversationId,
            start = CoroutineStart.LAZY,
        ) {
            runCatching {
                createContextCheckpoint(
                    conversationId = conversationId,
                    messages = messages,
                    additionalPrompt = "",
                    targetTokens = AUTO_COMPACT_SUMMARY_TOKENS,
                    recentTokenBudget = automaticRecentTokenBudget(
                        contextWindowTokens = contextWindowTokens,
                        sourcePromptTokens = sourcePromptTokenEstimate,
                        projectedHistoryTokens = projectedHistoryTokens,
                    ),
                    trigger = ContextCompactionTrigger.AUTO,
                    sourceTokenEstimate = sourcePromptTokenEstimate,
                    forceCompaction = false,
                    active = false,
                )
            }.onFailure { error ->
                if (error !is CancellationException) {
                    Log.w(TAG, "Silent background context compaction did not complete", error)
                }
            }
        }
        if (session.trySetCompactionJob(job)) {
            Log.i(
                TAG,
                "Scheduled silent context checkpoint: sourceTokens=$sourcePromptTokenEstimate, " +
                    "activateAt=${policy.activateAtTokens}"
            )
            job.start()
        } else {
            job.cancel()
        }
    }

    private suspend fun activatePreparedContextCheckpoint(
        conversationId: Uuid,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val activatedMessages = messages.activateLatestPreparedContextCheckpoint()
        if (activatedMessages == messages) return messages

        var persistedMessages: List<UIMessage>? = null
        mutateAndSaveConversation(conversationId) { current ->
            if (current.currentMessages != messages) {
                throw IllegalStateException(
                    context.getString(R.string.chat_page_compress_conversation_changed)
                )
            }
            current.updateCurrentMessages(activatedMessages)
                .also { persistedMessages = it.currentMessages }
        }
        Log.i(TAG, "Activated prepared context checkpoint for $conversationId")
        return persistedMessages
            ?: throw IllegalStateException(
                context.getString(R.string.chat_page_compress_conversation_changed)
            )
    }

    suspend fun compressConversation(
        conversationId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
    ): Result<Unit> = runCatching {
        val messages = getConversationFlow(conversationId).value.currentMessages
        createContextCheckpoint(
            conversationId = conversationId,
            messages = messages,
            additionalPrompt = additionalPrompt,
            targetTokens = targetTokens,
            recentTokenBudget = AUTO_COMPACT_RECENT_TOKEN_BUDGET,
            trigger = ContextCompactionTrigger.MANUAL,
            sourceTokenEstimate = estimatePromptTokens(messages, emptyList()),
            forceCompaction = true,
            active = true,
        )
    }

    private suspend fun createContextCheckpoint(
        conversationId: Uuid,
        messages: List<UIMessage>,
        additionalPrompt: String,
        targetTokens: Int,
        recentTokenBudget: Int,
        trigger: ContextCompactionTrigger,
        sourceTokenEstimate: Int,
        forceCompaction: Boolean,
        active: Boolean,
    ): List<UIMessage> {
        val plan = buildContextCompactionPlan(
            messages = messages,
            recentTokenBudget = recentTokenBudget,
            forceCompaction = forceCompaction,
        ) ?: throw IllegalStateException(
            context.getString(R.string.chat_page_compress_not_enough_turns)
        )

        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)
        val safeTargetTokens = targetTokens.coerceIn(500, 16_000)

        suspend fun summarizeContent(contentToCompress: String): String {
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to safeTargetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(
                    model = model,
                    maxTokens = safeTargetTokens,
                ),
            )

            return result.choices[0].message?.toText()?.trim()
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException(
                    context.getString(R.string.chat_page_compress_empty_summary)
                )
        }

        var summaries = splitCompactionContent(
            renderMessagesForCompaction(
                priorCheckpointSummary = plan.priorCheckpointSummary,
                messages = plan.messagesToCompress,
            )
        ).map { chunk ->
            summarizeContent(chunk)
        }
        while (summaries.size > 1) {
            val mergeInput = summaries.mapIndexed { index, summary ->
                "[PARTIAL CHECKPOINT ${index + 1}]\n$summary"
            }.joinToString("\n\n")
            summaries = splitCompactionContent(mergeInput).map { chunk ->
                summarizeContent(chunk)
            }
        }

        val summary = summaries.singleOrNull()
            ?: throw IllegalStateException(
                context.getString(R.string.chat_page_compress_empty_summary)
            )
        val checkpointedMessages = applyContextCheckpoint(
            messages = messages,
            plan = plan,
            summary = summary,
            sourceTokenEstimate = sourceTokenEstimate,
            trigger = trigger,
            createdAtEpochMillis = System.currentTimeMillis(),
            active = active,
        )
        Log.i(
            TAG,
            "Context checkpoint prepared: trigger=${trigger.name.lowercase()}, " +
                "sourceTokens=$sourceTokenEstimate, " +
                "compressedMessages=${plan.messagesToCompress.size}, " +
                "keptMessages=${plan.messagesToKeep.size}"
        )

        var persistedMessages: List<UIMessage>? = null
        mutateAndSaveConversation(conversationId) { current ->
            if (current.currentMessages != messages) {
                throw IllegalStateException(
                    context.getString(R.string.chat_page_compress_conversation_changed)
                )
            }
            current.updateCurrentMessages(checkpointedMessages)
                .let { updated ->
                    if (trigger == ContextCompactionTrigger.MANUAL) {
                        updated.copy(chatSuggestions = emptyList())
                    } else {
                        updated
                    }
                }
                .also { persistedMessages = it.currentMessages }
        }
        return persistedMessages
            ?: throw IllegalStateException(
                context.getString(R.string.chat_page_compress_conversation_changed)
            )
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation): Conversation? {
        if (conversation.id != conversationId) return null
        val session = getOrCreateSession(conversationId)
        val safeConversation = session.attachmentRedactions().fold(conversation) { current, redaction ->
            current.redactMonthlySpendingAttachments(
                redaction = redaction,
                placeholder = context.getString(R.string.ledger_attachment_cleanup_placeholder),
            )
        }
        val cleanupManagedUris = session.attachmentRedactions()
            .flatMapTo(mutableSetOf()) { it.attachmentUris }
        checkFilesDelete(
            newConversation = safeConversation,
            oldConversation = session.state.value,
            excludedUris = cleanupManagedUris,
        )
        session.state.value = safeConversation
        return safeConversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
    */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        val session = sessions[conversationId]
        if (session == null) {
            conversationRepo.updateConversationFolderId(conversationId, folderId)
            return
        }
        session.mutationMutex.withLock {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
            conversationRepo.updateConversationFolderId(conversationId, folderId)
        }
    }

    /**
     * Changes only assistant/folder columns in Room. If the conversation has an active session,
     * synchronize its in-memory metadata under the same mutation lock first. This deliberately
     * avoids writing a previously loaded full message snapshot over a concurrent attachment cleanup.
     */
    suspend fun moveConversationToAssistant(conversationId: Uuid, assistantId: Uuid) {
        val session = sessions[conversationId]
        if (session == null) {
            conversationRepo.updateConversationAssistantAndClearFolder(
                conversationId = conversationId,
                assistantId = assistantId,
            )
            return
        }
        session.mutationMutex.withLock {
            updateConversationState(conversationId) {
                it.copy(assistantId = assistantId, folderId = null)
            }
            conversationRepo.updateConversationAssistantAndClearFolder(
                conversationId = conversationId,
                assistantId = assistantId,
            )
        }
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(
        newConversation: Conversation,
        oldConversation: Conversation,
        excludedUris: Set<String> = emptySet(),
    ) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            file.toString() !in excludedUris && newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val session = getOrCreateSession(conversationId)
        session.mutationMutex.withLock {
            saveConversationLocked(conversationId, conversation)
        }
    }

    private suspend fun saveConversationLocked(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val safeConversation = updateConversation(
            conversationId = conversationId,
            conversation = conversation.copy(),
        ) ?: return

        if (!exists) {
            conversationRepo.insertConversation(safeConversation)
        } else {
            conversationRepo.updateConversation(safeConversation)
        }
    }

    private suspend fun mutateAndSaveConversation(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation?,
    ) {
        val session = getOrCreateSession(conversationId)
        session.mutationMutex.withLock {
            val updated = transform(session.state.value) ?: return@withLock
            saveConversationLocked(conversationId, updated)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(
            parts = parts,
            assistant = assistant,
            conversationId = conversationId,
        )
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(
            conversationId,
            currentConversation.copy(messageNodes = updatedNodes).withoutContextCheckpoints()
        )
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            userPromptSnapshot = currentConversation.userPromptSnapshot,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(
            conversationId,
            currentConversation.copy(messageNodes = updatedNodes).withoutContextCheckpoints()
        )
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation.withoutContextCheckpoints())
        memoryDocumentRepository.revokeChatSources(
            conversationId = conversationId.toString(),
            messageId = messageId.toString(),
        )
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun Conversation.withoutContextCheckpoints(): Conversation = copy(
        messageNodes = messageNodes.map { node ->
            node.copy(messages = node.messages.clearContextCheckpoints())
        }
    )

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
        assistantTaskRepository.findActiveForConversation(conversationId.toString())?.let { task ->
            runCatching { assistantTaskRepository.stop(task.id) }
                .onFailure { Log.w(TAG, "Unable to mark assistant task stopped", it) }
        }
    }
}

internal fun normalizeWorkSessionTitle(value: String): String? = value
    .lineSequence()
    .map(String::trim)
    .firstOrNull(String::isNotBlank)
    ?.trim('"', '\'', '“', '”', '‘', '’')
    ?.take(80)
    ?.trim()
    ?.takeIf(String::isNotBlank)
