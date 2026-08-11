package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.supervisorScope
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.core.ToolExecutionException
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildMemoryDocumentTools
import me.rerere.rikkahub.data.ai.tools.validateMemoryDocumentChatSources
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryDocument
import me.rerere.rikkahub.data.repository.MemoryDocumentRepository
import me.rerere.rikkahub.data.task.AssistantTaskStep
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

internal fun toolExecutionLogMessage(toolName: String) = "generateText: executing tool $toolName"

internal data class ToolInteractionPreparation(
    val tools: List<UIMessagePart.Tool>,
    val isWaitingForUserAnswer: Boolean,
)

/**
 * Normal chat only pauses for an explicit business question. `needsApproval` remains on [Tool]
 * for persisted legacy configuration, but must not create a new approval stop in this runtime.
 */
internal fun prepareToolsForUserAnswer(
    tools: List<UIMessagePart.Tool>,
    definitions: List<Tool>,
): ToolInteractionPreparation {
    val definitionsByName = definitions.associateBy(Tool::name)
    var isWaitingForUserAnswer = false
    val prepared = tools.map { tool ->
        val requiresUserAnswer = definitionsByName[tool.toolName]?.requiresUserAnswer == true
        when {
            requiresUserAnswer && tool.approvalState is ToolApprovalState.Auto -> {
                isWaitingForUserAnswer = true
                tool.copy(approvalState = ToolApprovalState.Pending)
            }

            requiresUserAnswer && tool.approvalState is ToolApprovalState.Pending -> {
                isWaitingForUserAnswer = true
                tool
            }

            // A persisted ordinary-tool Pending state was created by the retired approval flow.
            // Normalize it so resuming old conversations cannot leave a tool permanently stuck.
            !requiresUserAnswer && tool.approvalState is ToolApprovalState.Pending ->
                tool.copy(approvalState = ToolApprovalState.Auto)

            else -> tool
        }
    }
    return ToolInteractionPreparation(prepared, isWaitingForUserAnswer)
}

internal fun sanitizeToolInputsForStorage(
    messages: List<UIMessage>,
    tools: List<Tool>,
): List<UIMessage> {
    val toolsByName = tools.associateBy(Tool::name)
    return messages.map { message ->
        message.copy(
            parts = message.parts.map partsLoop@{ part ->
                if (part is UIMessagePart.Tool) {
                    val tool = toolsByName[part.toolName] ?: return@partsLoop part
                    val sanitizedInput = runCatching {
                        tool.sanitizeInputForStorage(part.input)
                    }.getOrDefault("{}")
                    part.copy(input = sanitizedInput)
                } else {
                    part
                }
            },
        )
    }
}

internal suspend fun <T, R> executeInOrderedBatches(
    items: List<T>,
    canRunInParallel: (T) -> Boolean,
    execute: suspend (T) -> R,
): List<R> {
    val results = arrayListOf<R>()
    var index = 0
    while (index < items.size) {
        val current = items[index]
        if (!canRunInParallel(current)) {
            results += execute(current)
            index++
            continue
        }
        val batch = items.drop(index).takeWhile(canRunInParallel)
        results += supervisorScope { batch.map { item -> async { execute(item) } }.awaitAll() }
        index += batch.size
    }
    return results
}

internal class MemoryDocumentPromptSnapshot(initialDocuments: List<MemoryDocument>) {
    private var currentDocuments = initialDocuments
    private var invalidated = false

    fun invalidate() {
        invalidated = true
    }

    suspend fun resolve(refresh: suspend () -> List<MemoryDocument>): List<MemoryDocument> {
        if (invalidated) {
            currentDocuments = refresh()
            invalidated = false
        }
        return currentDocuments
    }
}

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

data class PromptCompactionResult(
    val messages: List<UIMessage>,
    val maximumPromptTokens: Int,
)

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryDocumentRepository: MemoryDocumentRepository,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memoryDocuments: List<MemoryDocument>? = null,
        memoryConversationId: String? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        onPromptPrepared: suspend (estimatedTokens: Int, messages: List<UIMessage>) -> PromptCompactionResult? =
            { _, _ -> null },
        onTaskStep: suspend (AssistantTaskStep) -> Unit = {},
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = sanitizeToolInputsForStorage(messages, tools)
        val memoryScopeId = if (assistant.enableMemory) {
            if (assistant.useGlobalMemory) MemoryDocumentRepository.GLOBAL_SCOPE_ID else assistant.id.toString()
        } else {
            null
        }
        val memoryPromptSnapshot = MemoryDocumentPromptSnapshot(memoryDocuments.orEmpty())
        var memoryWriteFinalized = false
        val reportedToolCalls = mutableSetOf<String>()
        var toolOrdinal = 0

        suspend fun reportTaskSteps(
            toolParts: List<UIMessagePart.Tool>,
            definitions: List<Tool>,
        ) {
            val definitionsByName = definitions.associateBy(Tool::name)
            toolParts.forEach { toolPart ->
                if (reportedToolCalls.add(toolPart.toolCallId)) {
                    toolOrdinal += 1
                    val definition = definitionsByName[toolPart.toolName]
                    onTaskStep(
                        AssistantTaskStep(
                            toolName = toolPart.toolName,
                            toolCallId = toolPart.toolCallId,
                            input = toolPart.input,
                            ordinal = toolOrdinal,
                            requiresUserAnswer = definition?.requiresUserAnswer == true,
                            hasUserAnswer = toolPart.approvalState is ToolApprovalState.Answered,
                        )
                    )
                }
            }
        }

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")
            val promptMemoryDocuments = if (memoryScopeId != null) {
                memoryPromptSnapshot.resolve {
                    memoryDocumentRepository.getPromptDocuments(memoryScopeId)
                }
            } else {
                emptyList()
            }

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools")
                if (memoryScopeId != null) {
                    buildMemoryDocumentTools(
                        json = json,
                        onFinalize = {
                            check(!memoryWriteFinalized) { "memory_write can be called only once per chat run" }
                            memoryWriteFinalized = true
                        },
                        onRead = { path -> memoryDocumentRepository.read(memoryScopeId, path) },
                        onWrite = { path, ifVersion, name, description, aliases, content, sources ->
                            val conversationId = memoryConversationId
                                ?: error("Memory writes require a persisted conversation")
                            memoryDocumentRepository.writeFromChat(
                                contextScopeId = memoryScopeId,
                                rawPath = path,
                                expectedVersion = ifVersion,
                                name = name,
                                description = description,
                                aliases = aliases,
                                content = content,
                                sources = validateMemoryDocumentChatSources(sources, conversationId, messages),
                            ).also { memoryPromptSnapshot.invalidate() }
                        },
                        onReplace = { path, ifVersion, oldText, newText, sources ->
                            val conversationId = memoryConversationId
                                ?: error("Memory writes require a persisted conversation")
                            memoryDocumentRepository.replaceFromChat(
                                contextScopeId = memoryScopeId,
                                rawPath = path,
                                expectedVersion = ifVersion,
                                oldText = oldText,
                                newText = newText,
                                sources = validateMemoryDocumentChatSources(sources, conversationId, messages),
                            ).also { memoryPromptSnapshot.invalidate() }
                        },
                        onAppend = { path, ifVersion, content, sources ->
                            val conversationId = memoryConversationId
                                ?: error("Memory writes require a persisted conversation")
                            memoryDocumentRepository.appendFromChat(
                                contextScopeId = memoryScopeId,
                                rawPath = path,
                                expectedVersion = ifVersion,
                                content = content,
                                sources = validateMemoryDocumentChatSources(sources, conversationId, messages),
                            ).also { memoryPromptSnapshot.invalidate() }
                        },
                        onDelete = { path, ifVersion ->
                            memoryDocumentRepository.delete(memoryScopeId, path, ifVersion)
                            memoryPromptSnapshot.invalidate()
                        },
                    ).let(this::addAll)
                }
                addAll(tools)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                var internalMessages = buildInternalMessages(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    transformers = inputTransformers,
                    model = model,
                    tools = toolsInternal,
                    memoryDocuments = promptMemoryDocuments,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                )
                if (stepIndex == 0) {
                    val estimatedTokens = estimatePromptTokens(internalMessages, toolsInternal)
                    onPromptPrepared(estimatedTokens, messages)?.let { compaction ->
                        messages = sanitizeToolInputsForStorage(compaction.messages, toolsInternal)
                        internalMessages = buildInternalMessages(
                            assistant = assistant,
                            settings = settings,
                            messages = messages,
                            transformers = inputTransformers,
                            model = model,
                            tools = toolsInternal,
                            memoryDocuments = promptMemoryDocuments,
                            processingStatus = processingStatus,
                            conversationSystemPrompt = conversationSystemPrompt,
                            conversationModeInjectionIds = conversationModeInjectionIds,
                            conversationLorebookIds = conversationLorebookIds,
                            workspaceCwd = workspaceCwd,
                        )
                        val compactedEstimate = estimatePromptTokens(internalMessages, toolsInternal)
                        check(compactedEstimate < compaction.maximumPromptTokens) {
                            "Context is still too large after compaction " +
                                "($compactedEstimate >= ${compaction.maximumPromptTokens} tokens). " +
                                "The latest turn or stable context must be reduced."
                        }
                    }
                }
                generateInternal(
                    assistant = assistant,
                    messages = messages,
                    internalMessages = internalMessages,
                    onUpdateMessages = {
                        messages = sanitizeToolInputsForStorage(it, toolsInternal).transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    stream = assistant.streamOutput,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                val interaction = prepareToolsForUserAnswer(tools, toolsInternal)
                val updatedTools = interaction.tools

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // Only a business question pauses normal chat. Permissions and connection setup
                // stay inside their respective tool/platform boundaries.
                if (interaction.isWaitingForUserAnswer) {
                    reportTaskSteps(updatedTools, toolsInternal)
                    Log.i(TAG, "generateText: waiting for tool user answer")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            reportTaskSteps(toolsToProcess, toolsInternal)

            // Handle tools. Stateful tools preserve their original serial order; consecutive
            // explicitly read-only tools may execute together and are reassembled in model order.
            suspend fun executeTool(tool: UIMessagePart.Tool): UIMessagePart.Tool? {
                return when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        tool.copy(output = listOf(UIMessagePart.Text(answer)))
                    }

                    is ToolApprovalState.Pending -> null

                    else -> {
                        try {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, toolExecutionLogMessage(toolDef.name))
                            val result = toolDef.execute(args)
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            tool.copy(
                                output = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess)
                            )
                        } catch (throwable: Throwable) {
                            if (throwable is CancellationException) throw throwable
                            Log.w(TAG, "generateText: tool ${tool.toolName} failed")
                            val errorCode = (throwable as? ToolExecutionException)?.code
                                ?: "TOOL_EXECUTION_FAILED"
                            tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(
                                                        "[$errorCode] 工具执行失败，请检查连接、权限或输入后重试"
                                                    )
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            val executedTools = executeInOrderedBatches(
                items = toolsToProcess,
                canRunInParallel = { candidate ->
                    val candidateDef = toolsInternal.find { it.name == candidate.toolName }
                    candidate.approvalState !is ToolApprovalState.Denied &&
                        candidate.approvalState !is ToolApprovalState.Answered &&
                        candidate.approvalState !is ToolApprovalState.Pending &&
                        candidateDef?.executionMode == ToolExecutionMode.PARALLEL_READ_ONLY
                },
                execute = { executeTool(it) },
            ).filterNotNull()

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun buildInternalMessages(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        transformers: List<MessageTransformer>,
        model: Model,
        tools: List<Tool>,
        memoryDocuments: List<MemoryDocument>,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
    ): List<UIMessage> {
        val projection = messages.projectContextForPrompt()
        return buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryDocumentPrompt(documents = memoryDocuments))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, projection.messages))
                }
                projection.checkpointSummary?.let { summary ->
                    appendLine()
                    append(renderConversationCheckpoint(summary))
                }
                messages.latestRuntimeContext()
                    ?.let(::renderRuntimeContextForPrompt)
                    ?.let { runtimeContext ->
                        appendLine()
                        append(runtimeContext)
                    }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(projection.messages.limitContext(assistant.contextMessageSize))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        messages: List<UIMessage>,
        internalMessages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        stream: Boolean,
    ) {
        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect {
                messages = messages.handleMessageChunk(chunk = it, model = model)
                it.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
        } else {
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
