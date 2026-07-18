package me.rerere.rikkahub.data.workflow.codex

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import me.rerere.rikkahub.data.db.entity.CodexCatalogChunkEntity
import me.rerere.rikkahub.data.db.dao.CodexCatalogDAO
import me.rerere.rikkahub.data.db.entity.CodexCatalogSyncEntity
import me.rerere.rikkahub.data.db.entity.CodexItemEntity
import me.rerere.rikkahub.data.db.entity.CodexMachineEntity
import me.rerere.rikkahub.data.db.entity.CodexProjectEntity
import me.rerere.rikkahub.data.db.entity.CodexThreadEntity
import me.rerere.rikkahub.data.db.entity.CodexTurnEntity

interface WireCatalogSink {
    suspend fun applySnapshot(snapshot: CatalogSnapshotPayload, syncedAt: Long = System.currentTimeMillis()): Boolean
    suspend fun applySnapshotChunk(chunk: CatalogSnapshotChunkPayload, receivedAt: Long = System.currentTimeMillis()): Boolean
    suspend fun applyThreadDetail(detail: ThreadDetailPayload)
}

class CodexCatalogRepository(
    private val dao: CodexCatalogDAO,
    private val json: Json,
) : WireCatalogSink {
    fun observeProjects(): Flow<List<CodexProject>> = combine(
        dao.observeMachines(),
        dao.observeProjects(),
        dao.observeThreads(),
    ) { machineEntities, projectEntities, threadEntities ->
        val machines = machineEntities.associate { it.machineId to it.toModel() }
        val threads = threadEntities.groupBy(CodexThreadEntity::projectId)
        projectEntities.map { entity ->
            entity.toModel(
                machine = machines[entity.machineId],
                threads = threads[entity.projectId].orEmpty().map(CodexThreadEntity::toModel),
            )
        }
    }

    fun observeMachines(): Flow<List<CodexMachine>> =
        dao.observeMachines().map { machines -> machines.map(CodexMachineEntity::toModel) }

    fun observeThread(machineId: String, threadId: String): Flow<CodexThread?> =
        dao.observeThread(machineId, threadId).map { it?.toModel() }

    fun observeThreadDetail(machineId: String, threadId: String): Flow<CodexThreadDetail> = combine(
        dao.observeThread(machineId, threadId),
        dao.observeTurns(machineId, threadId),
        dao.observeItems(machineId, threadId),
    ) { thread, turns, items ->
        val itemsByTurn = items.groupBy(CodexItemEntity::turnId)
        CodexThreadDetail(
            thread = thread?.toModel(),
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
                        )
                    },
                )
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

private fun CodexProjectEntity.toModel(machine: CodexMachine?, threads: List<CodexThread>) = CodexProject(
    projectId = projectId,
    machineId = machineId,
    displayName = displayName,
    canonicalRoot = canonicalRoot,
    vcsKind = vcsKind,
    branch = vcsBranch,
    updatedAt = updatedAt,
    machine = machine,
    threads = threads.sortedByDescending(CodexThread::recencyAt),
)

private fun CodexThreadEntity.toModel() = CodexThread(
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
