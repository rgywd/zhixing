package me.rerere.rikkahub.data.workflow.codex

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import me.rerere.rikkahub.data.db.entity.CodexCatalogChunkEntity
import me.rerere.rikkahub.data.db.dao.CodexCatalogDAO
import me.rerere.rikkahub.data.db.entity.CodexCatalogSyncEntity
import me.rerere.rikkahub.data.db.entity.CodexItemEntity
import me.rerere.rikkahub.data.db.entity.CodexMachineEntity
import me.rerere.rikkahub.data.db.entity.CodexProjectEntity
import me.rerere.rikkahub.data.db.entity.CodexProjectPreferenceEntity
import me.rerere.rikkahub.data.db.entity.CodexApprovalEntity
import me.rerere.rikkahub.data.db.entity.CodexRuntimeBindingEntity
import me.rerere.rikkahub.data.db.entity.CodexRuntimeCatalogEntity
import me.rerere.rikkahub.data.db.entity.CodexRuntimeSettingsEntity
import me.rerere.rikkahub.data.db.entity.CodexAttachmentEntity
import me.rerere.rikkahub.data.db.entity.CodexDraftEntity
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.CodexThreadEntity
import me.rerere.rikkahub.data.db.entity.CodexThreadPreferenceEntity
import me.rerere.rikkahub.data.db.entity.CodexTurnEntity

interface WireCatalogSink {
    suspend fun applySnapshot(snapshot: CatalogSnapshotPayload, syncedAt: Long = System.currentTimeMillis()): Boolean
    suspend fun applySnapshotChunk(chunk: CatalogSnapshotChunkPayload, receivedAt: Long = System.currentTimeMillis()): Boolean
    suspend fun applyThreadDetail(detail: ThreadDetailPayload)
    suspend fun applyThreadDetailChunk(chunk: ThreadDetailChunkPayload, receivedAt: Long = System.currentTimeMillis()): Boolean
    suspend fun applyRuntimeEvent(event: RuntimeEventPayload) = Unit
    suspend fun applyCommandResult(result: CommandResultPayload) = Unit
    suspend fun applyRuntimeCatalog(catalog: RuntimeCatalogPayload) = Unit
}

class CodexCatalogRepository(
    private val dao: CodexCatalogDAO,
    private val json: Json,
) : WireCatalogSink {
    fun observeRuntimeCatalog(machineId: String, cwd: String): Flow<RuntimeCatalogPayload?> =
        dao.observeRuntimeCatalog(machineId, cwd).map { entity ->
            entity?.let { runCatching { json.decodeFromString<RuntimeCatalogPayload>(it.payloadJson) }.getOrNull() }
        }

    fun observeRuntimeSettings(machineId: String, threadId: String): Flow<CodexRuntimeSettingsState> =
        dao.observeRuntimeSettings(machineId, threadId).map { it?.toModel() ?: CodexRuntimeSettingsState() }

    override suspend fun applyRuntimeCatalog(catalog: RuntimeCatalogPayload) {
        dao.upsertRuntimeCatalog(
            CodexRuntimeCatalogEntity(
                machineId = catalog.machineId,
                cwd = catalog.cwd,
                payloadJson = json.encodeToString(catalog),
                updatedAt = catalog.generatedAt,
            )
        )
    }

    suspend fun rememberAttachment(
        machineId: String,
        threadId: String,
        remotePath: String,
        localUri: String,
        fileName: String,
        mime: String,
    ) {
        dao.upsertAttachment(
            CodexAttachmentEntity(machineId, threadId, remotePath, localUri, fileName, mime, System.currentTimeMillis())
        )
    }

    suspend fun loadDraft(machineId: String, threadId: String): List<UIMessagePart> =
        dao.draft(machineId, threadId)?.let { entity ->
            runCatching { json.decodeFromString<List<UIMessagePart>>(entity.contentsJson) }.getOrDefault(emptyList())
        }.orEmpty()

    suspend fun saveDraft(machineId: String, threadId: String, contents: List<UIMessagePart>) {
        val meaningful = contents.any { it !is UIMessagePart.Text || it.text.isNotBlank() }
        if (!meaningful) {
            dao.deleteDraft(machineId, threadId)
        } else {
            dao.upsertDraft(CodexDraftEntity(machineId, threadId, json.encodeToString(contents), System.currentTimeMillis()))
        }
    }
    fun observeProjects(): Flow<List<CodexProject>> = combine(
        dao.observeMachines(),
        dao.observeProjects(),
        dao.observeThreads(),
        dao.observeProjectPreferences(),
        dao.observePinnedThreadPreferences(),
    ) { machineEntities, projectEntities, threadEntities, projectPreferences, threadPreferences ->
        val machines = machineEntities.associate { it.machineId to it.toModel() }
        val projectPreferenceById = projectPreferences.associateBy(CodexProjectPreferenceEntity::projectId)
        val pinnedThreadIds = threadPreferences.mapTo(hashSetOf()) { it.machineId to it.threadId }
        val threads = threadEntities.groupBy(CodexThreadEntity::projectId)
        projectEntities.map { entity ->
            val preference = projectPreferenceById[entity.projectId]
            entity.toModel(
                machine = machines[entity.machineId],
                threads = threads[entity.projectId].orEmpty().map { thread ->
                    thread.toModel(isPinned = thread.machineId to thread.threadId in pinnedThreadIds)
                },
                isPinned = preference?.isPinned == true,
                isHidden = preference?.isHidden == true,
            )
        }
    }

    suspend fun setProjectPinned(projectId: String, isPinned: Boolean) {
        val current = dao.projectPreference(projectId)
        dao.upsertProjectPreference(
            CodexProjectPreferenceEntity(
                projectId = projectId,
                isPinned = isPinned,
                isHidden = if (isPinned) false else current?.isHidden == true,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun setProjectHidden(projectId: String, isHidden: Boolean) {
        val current = dao.projectPreference(projectId)
        dao.upsertProjectPreference(
            CodexProjectPreferenceEntity(
                projectId = projectId,
                isPinned = if (isHidden) false else current?.isPinned == true,
                isHidden = isHidden,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun setThreadPinned(machineId: String, threadId: String, isPinned: Boolean) {
        dao.upsertThreadPreference(
            CodexThreadPreferenceEntity(
                machineId = machineId,
                threadId = threadId,
                isPinned = isPinned,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    fun observeMachines(): Flow<List<CodexMachine>> =
        dao.observeMachines().map { machines -> machines.map(CodexMachineEntity::toModel) }

    fun observeThread(machineId: String, threadId: String): Flow<CodexThread?> = combine(
        dao.observeThread(machineId, threadId),
        dao.observeThreadPreference(machineId, threadId),
    ) { thread, preference -> thread?.toModel(isPinned = preference?.isPinned == true) }

    fun observeThreadDetail(machineId: String, threadId: String): Flow<CodexThreadDetail> = combine(
        dao.observeThread(machineId, threadId),
        dao.observeTurns(machineId, threadId),
        dao.observeItems(machineId, threadId),
        dao.observeApprovals(machineId, threadId),
        dao.observeThreadPreference(machineId, threadId),
        dao.observeProjectForThread(machineId, threadId),
        dao.observeAttachments(machineId, threadId),
    ) { values ->
        val thread = values[0] as CodexThreadEntity?
        @Suppress("UNCHECKED_CAST") val turns = values[1] as List<CodexTurnEntity>
        @Suppress("UNCHECKED_CAST") val items = values[2] as List<CodexItemEntity>
        @Suppress("UNCHECKED_CAST") val approvals = values[3] as List<CodexApprovalEntity>
        val preference = values[4] as CodexThreadPreferenceEntity?
        val project = values[5] as CodexProjectEntity?
        @Suppress("UNCHECKED_CAST") val attachments = values[6] as List<CodexAttachmentEntity>
        val itemsByTurn = items.groupBy(CodexItemEntity::turnId)
        CodexThreadDetail(
            thread = thread?.toModel(isPinned = preference?.isPinned == true),
            turns = turns.map { turn ->
                CodexTurn(
                    turnId = turn.turnId,
                    status = turn.status,
                    startedAt = turn.startedAt,
                    completedAt = turn.completedAt,
                    error = turn.error,
                    items = itemsByTurn[turn.turnId].orEmpty().map { item ->
                        CodexItem(
                            itemId = item.itemId,
                            type = item.type,
                            rawType = item.rawType,
                            role = item.role,
                            text = item.text,
                            status = item.status,
                            raw = runCatching { json.parseToJsonElement(item.rawJson).jsonObject }
                                .getOrDefault(JsonObject(emptyMap())),
                        )
                    },
                )
            },
            approvals = approvals.map { approval ->
                CodexApproval(
                    approvalId = approval.approvalId,
                    kind = approval.kind,
                    summary = approval.summary,
                    createdAt = approval.createdAt,
                    payload = runCatching { json.parseToJsonElement(approval.payloadJson).jsonObject }
                        .getOrDefault(JsonObject(emptyMap())),
                )
            },
            cwd = project?.canonicalRoot,
            attachments = attachments.associate { entity ->
                entity.remotePath to CodexAttachment(entity.remotePath, entity.localUri, entity.fileName, entity.mime)
            },
        )
    }

    suspend fun search(query: String): List<CodexThread> =
        query.trim().takeIf(String::isNotEmpty)?.let { dao.search(it).map(CodexThreadEntity::toModel) }.orEmpty()

    override suspend fun applySnapshot(snapshot: CatalogSnapshotPayload, syncedAt: Long): Boolean {
        val current = dao.revision(snapshot.machine.machineId) ?: -1
        if (snapshot.revision < current) return false
        dao.replaceSnapshot(
            machine = snapshot.machine.toEntity(),
            projects = snapshot.projects.map(CatalogProjectPayload::toEntity),
            threads = snapshot.threads.map(CatalogThreadPayload::toEntity),
            sync = CodexCatalogSyncEntity(
                machineId = snapshot.machine.machineId,
                revision = snapshot.revision,
                generatedAt = snapshot.generatedAt,
                syncedAt = syncedAt,
            ),
        )
        return true
    }

    override suspend fun applySnapshotChunk(chunk: CatalogSnapshotChunkPayload, receivedAt: Long): Boolean {
        require(chunk.chunkCount in 1..MAX_CATALOG_CHUNKS)
        require(chunk.chunkIndex in 0 until chunk.chunkCount)
        val content = Base64.getUrlDecoder().decode(chunk.contentBase64)
        require(content.sha256() == chunk.chunkHash) { "catalog chunk hash mismatch" }
        dao.deleteOldCatalogChunks(receivedAt - CHUNK_RETENTION_MS)
        dao.upsertCatalogChunk(
            CodexCatalogChunkEntity(
                snapshotId = chunk.snapshotId,
                chunkIndex = chunk.chunkIndex,
                chunkCount = chunk.chunkCount,
                revision = chunk.revision,
                generatedAt = chunk.generatedAt,
                machineJson = json.encodeToString(chunk.machine),
                contentHash = chunk.contentHash,
                chunkHash = chunk.chunkHash,
                contentBase64 = chunk.contentBase64,
                receivedAt = receivedAt,
            )
        )
        val chunks = dao.catalogChunks(chunk.snapshotId)
        if (chunks.size != chunk.chunkCount) return false
        val snapshot = CatalogChunkAssembler.assemble(chunks, json)
        val applied = applySnapshot(snapshot, receivedAt)
        dao.deleteCatalogChunks(chunk.snapshotId)
        return applied
    }

    override suspend fun applyThreadDetail(detail: ThreadDetailPayload) {
        val turns = detail.turns.mapIndexed { index, turn ->
            CodexTurnEntity(
                machineId = detail.machineId,
                threadId = detail.threadId,
                turnId = turn.turnId,
                status = turn.status,
                startedAt = turn.startedAt,
                completedAt = turn.completedAt,
                durationMs = turn.durationMs,
                error = turn.error,
                position = index,
            )
        }
        val items = detail.turns.flatMap { turn ->
            turn.items.mapIndexed { index, item ->
                CodexItemEntity(
                    machineId = detail.machineId,
                    threadId = detail.threadId,
                    turnId = turn.turnId,
                    itemId = item.itemId,
                    type = item.type,
                    rawType = item.rawType,
                    role = item.role,
                    text = item.text,
                    status = item.status,
                    rawJson = json.encodeToString(item.raw),
                    position = index,
                )
            }
        }
        dao.replaceThreadDetail(detail.machineId, detail.threadId, turns, items)
    }

    override suspend fun applyThreadDetailChunk(chunk: ThreadDetailChunkPayload, receivedAt: Long): Boolean {
        require(chunk.chunkCount in 1..MAX_CATALOG_CHUNKS)
        require(chunk.chunkIndex in 0 until chunk.chunkCount)
        val bytes = Base64.getUrlDecoder().decode(chunk.contentBase64)
        require(bytes.sha256() == chunk.chunkHash) { "thread detail chunk hash mismatch" }
        dao.deleteOldCatalogChunks(receivedAt - CHUNK_RETENTION_MS)
        dao.upsertCatalogChunk(
            CodexCatalogChunkEntity(
                snapshotId = "detail:${chunk.detailId}",
                chunkIndex = chunk.chunkIndex,
                chunkCount = chunk.chunkCount,
                revision = 0,
                generatedAt = receivedAt,
                machineJson = "${chunk.machineId}\n${chunk.threadId}",
                contentHash = chunk.contentHash,
                chunkHash = chunk.chunkHash,
                contentBase64 = chunk.contentBase64,
                receivedAt = receivedAt,
            )
        )
        val stored = dao.catalogChunks("detail:${chunk.detailId}")
        if (stored.size != chunk.chunkCount) return false
        val detail = ThreadDetailChunkAssembler.assemble(stored, json)
        applyThreadDetail(detail)
        dao.deleteCatalogChunks("detail:${chunk.detailId}")
        return true
    }

    override suspend fun applyRuntimeEvent(event: RuntimeEventPayload) {
        if (event.type == "thread.settings" || event.type == "token.usage") {
            val current = dao.runtimeSettings(event.machineId, event.threadId)
            dao.upsertRuntimeSettings(
                CodexRuntimeSettingsEntity(
                    machineId = event.machineId,
                    threadId = event.threadId,
                    model = event.model ?: current?.model,
                    effort = event.effort ?: current?.effort,
                    serviceTier = event.serviceTier ?: current?.serviceTier,
                    permissions = event.permissions ?: current?.permissions,
                    usedTokens = event.usedTokens ?: current?.usedTokens,
                    contextWindow = event.contextWindow ?: current?.contextWindow,
                    updatedAt = event.at,
                )
            )
            return
        }
        when (event.type) {
            "runtime.connected" -> {
                dao.upsertRuntimeBinding(
                    CodexRuntimeBindingEntity(
                        machineId = event.machineId,
                        threadId = event.threadId,
                        bindingId = requireNotNull(event.bindingId),
                        state = event.state ?: "idle",
                        updatedAt = event.at,
                    )
                )
                dao.updateThreadRuntime(event.machineId, event.threadId, event.state ?: "idle", event.at)
            }
            "turn.started" -> {
                val turnId = requireNotNull(event.turnId)
                dao.upsertTurns(
                    listOf(
                        CodexTurnEntity(
                            machineId = event.machineId,
                            threadId = event.threadId,
                            turnId = turnId,
                            status = "inProgress",
                            startedAt = event.at,
                            completedAt = null,
                            durationMs = null,
                            error = null,
                            position = dao.turnPosition(event.machineId, event.threadId, turnId)
                                ?: dao.nextTurnPosition(event.machineId, event.threadId),
                        )
                    )
                )
                dao.updateThreadRuntime(event.machineId, event.threadId, "running", event.at)
            }
            "item.started", "item.delta", "item.completed" -> {
                val turnId = requireNotNull(event.turnId)
                val itemId = requireNotNull(event.itemId)
                val item = CodexRuntimeItemReducer.apply(null, event)
                dao.upsertItems(
                    listOf(
                        CodexItemEntity(
                            machineId = event.machineId,
                            threadId = event.threadId,
                            turnId = turnId,
                            itemId = itemId,
                            type = item.type,
                            rawType = item.rawType,
                            role = item.role,
                            text = item.text,
                            status = item.status,
                            rawJson = json.encodeToString(item.raw),
                            position = dao.itemPosition(event.machineId, event.threadId, turnId, itemId)
                                ?: dao.nextItemPosition(event.machineId, event.threadId, turnId),
                        )
                    )
                )
            }
            "turn.completed" -> {
                val turnId = requireNotNull(event.turnId)
                dao.upsertTurns(
                    listOf(
                        CodexTurnEntity(
                            machineId = event.machineId,
                            threadId = event.threadId,
                            turnId = turnId,
                            status = event.status ?: "completed",
                            startedAt = null,
                            completedAt = event.at,
                            durationMs = null,
                            error = null,
                            position = dao.turnPosition(event.machineId, event.threadId, turnId)
                                ?: dao.nextTurnPosition(event.machineId, event.threadId),
                        )
                    )
                )
                dao.updateThreadRuntime(event.machineId, event.threadId, "idle", event.at)
            }
            "approval.requested" -> dao.upsertApprovals(
                listOf(
                    CodexApprovalEntity(
                        machineId = event.machineId,
                        threadId = event.threadId,
                        approvalId = requireNotNull(event.approvalId),
                        kind = event.kind ?: "permission",
                        summary = event.summary ?: "请求授权",
                        payloadJson = json.encodeToString(event.payload),
                        createdAt = event.at,
                    )
                )
            )
            "approval.resolved" -> dao.deleteApproval(
                event.machineId,
                event.threadId,
                requireNotNull(event.approvalId),
            )
            "thread.archive" -> dao.updateThreadArchived(event.machineId, event.threadId, true, event.at)
            "thread.unarchive" -> dao.updateThreadArchived(event.machineId, event.threadId, false, event.at)
            "thread.delete" -> dao.deleteThreadWithDetails(event.machineId, event.threadId)
            "error" -> dao.updateThreadRuntime(event.machineId, event.threadId, "system_error", event.at)
        }
    }
}

private const val MAX_CATALOG_CHUNKS = 1_000
private const val CHUNK_RETENTION_MS = 24 * 60 * 60 * 1_000L


private fun CodexMachineEntity.toModel() = CodexMachine(
    machineId = machineId,
    displayName = displayName,
    platformFamily = platformFamily,
    platformOs = platformOs,
    agentVersion = agentVersion,
    codexVersion = codexVersion,
    runtimeWritable = runtimeWritable,
    compatibilityReason = compatibilityReason,
    lastSeenAt = lastSeenAt,
)

private fun CodexProjectEntity.toModel(
    machine: CodexMachine?,
    threads: List<CodexThread>,
    isPinned: Boolean,
    isHidden: Boolean,
) = CodexProject(
    projectId = projectId,
    machineId = machineId,
    displayName = displayName,
    canonicalRoot = canonicalRoot,
    existsOnDisk = existsOnDisk,
    vcsKind = vcsKind,
    branch = vcsBranch,
    updatedAt = updatedAt,
    isPinned = isPinned,
    isHidden = isHidden,
    machine = machine,
    threads = threads.sortedByDescending(CodexThread::recencyAt),
)

private fun CodexThreadEntity.toModel(isPinned: Boolean = false) = CodexThread(
    machineId = machineId,
    threadId = threadId,
    projectId = projectId,
    name = name,
    preview = preview,
    createdAt = createdAt,
    updatedAt = updatedAt,
    recencyAt = recencyAt,
    archived = archived,
    source = source,
    parentThreadId = parentThreadId,
    forkedFromId = forkedFromId,
    isSubagent = isSubagent,
    isAutomation = isAutomation,
    runtimeState = CodexRuntimeState.fromWire(runtimeState),
    rawStatus = rawStatus,
    isPinned = isPinned,
)

private fun CodexRuntimeSettingsEntity.toModel() = CodexRuntimeSettingsState(
    model = model,
    effort = effort,
    serviceTier = serviceTier,
    permissions = permissions,
    usedTokens = usedTokens,
    contextWindow = contextWindow,
    updatedAt = updatedAt,
)

private fun CatalogMachinePayload.toEntity() = CodexMachineEntity(
    machineId = machineId,
    displayName = displayName,
    platformFamily = platformFamily,
    platformOs = platformOs,
    agentVersion = agentVersion,
    codexVersion = codexVersion,
    schemaHash = schemaHash,
    runtimeWritable = runtimeWritable,
    compatibilityReason = compatibilityReason,
    operationsJson = Json.encodeToString(operations),
    lastSeenAt = lastSeenAt,
)

private fun CatalogProjectPayload.toEntity() = CodexProjectEntity(
    projectId = projectId,
    machineId = machineId,
    displayName = displayName,
    canonicalRoot = canonicalRoot,
    existsOnDisk = existsOnDisk,
    vcsKind = vcs.kind,
    vcsOriginUrl = vcs.originUrl,
    vcsBranch = vcs.branch,
    updatedAt = updatedAt,
)

private fun CatalogThreadPayload.toEntity() = CodexThreadEntity(
    machineId = machineId,
    threadId = threadId,
    projectId = projectId,
    name = name,
    preview = preview,
    createdAt = createdAt,
    updatedAt = updatedAt,
    recencyAt = recencyAt,
    archived = archived,
    source = source,
    threadSource = threadSource,
    parentThreadId = parentThreadId,
    forkedFromId = forkedFromId,
    isSubagent = isSubagent,
    isAutomation = isAutomation,
    runtimeState = runtimeState,
    rawStatus = rawStatus,
)
