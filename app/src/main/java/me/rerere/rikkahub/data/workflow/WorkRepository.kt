package me.rerere.rikkahub.data.workflow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import me.rerere.rikkahub.data.db.dao.WorkMachineDAO
import me.rerere.rikkahub.data.db.dao.WorkMessageDAO
import me.rerere.rikkahub.data.db.dao.WorkRepoPresetDAO
import me.rerere.rikkahub.data.db.dao.WorkSessionDAO
import me.rerere.rikkahub.data.db.entity.WorkMachineEntity
import me.rerere.rikkahub.data.db.entity.WorkMessageEntity
import me.rerere.rikkahub.data.db.entity.WorkRepoPresetEntity
import me.rerere.rikkahub.data.db.entity.WorkSessionEntity
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyAuthApi
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyCredentials
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyCredentialsStore
import me.rerere.rikkahub.ui.pages.workflow.happy.HappyMachine
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySession
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySocketClient
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySpawnResult
import me.rerere.rikkahub.ui.pages.workflow.happy.HappySyncApi

/**
 * 远程工作模块的单一数据源。
 *
 * UI 只观察 Room Flow；Happy HTTP/Socket 是内部远端源。加密密钥只存在于
 * 内存中的协议对象（[HappySession]/[HappyMachine]），Room 只缓存解密后的展示数据，
 * 进程重启后先展示缓存，快照刷新完成再恢复可写能力。
 */
class WorkRepository(
    private val appScope: CoroutineScope,
    private val credentialsStore: HappyCredentialsStore,
    private val authApi: HappyAuthApi,
    private val syncApi: HappySyncApi,
    private val socketClient: HappySocketClient,
    private val sessionDao: WorkSessionDAO,
    private val messageDao: WorkMessageDAO,
    private val machineDao: WorkMachineDAO,
    private val presetDao: WorkRepoPresetDAO,
) {
    // 协议对象内存缓存：携带解密密钥，供发送/审批/RPC 使用，绝不落盘
    private val protocolSessions = ConcurrentHashMap<String, HappySession>()
    private val protocolMachines = ConcurrentHashMap<String, HappyMachine>()
    private val snapshotMutex = Mutex()

    val connected = MutableStateFlow(credentialsStore.load() != null)
    val syncing = MutableStateFlow(false)
    val syncError = MutableStateFlow<Throwable?>(null)

    /** Socket 增量信号；订阅者按需节流后触发 refreshSnapshot/syncMessages */
    val updates: SharedFlow<Unit> = callbackFlow {
        val subscription = socketClient.addUpdateListener { trySend(Unit) }
        awaitClose { subscription.close() }
    }.shareIn(appScope, SharingStarted.WhileSubscribed())

    fun observeSessions(): Flow<List<WorkSession>> =
        sessionDao.observeAll().map { list -> list.map(WorkSessionEntity::toModel) }

    fun observeSession(id: String): Flow<WorkSession?> =
        sessionDao.observeById(id).map { it?.toModel() }

    suspend fun getSession(id: String): WorkSession? = sessionDao.getById(id)?.toModel()

    /** 本地缓存内搜索：会话标题/路径 + 消息内容（parts JSON 粗匹配） */
    suspend fun searchSessions(query: String): List<WorkSession> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val byMeta = sessionDao.searchByNameOrPath(trimmed)
        val byContent = messageDao.searchSessionIds(trimmed)
            .minus(byMeta.map(WorkSessionEntity::id).toSet())
            .takeIf { it.isNotEmpty() }
            ?.let { sessionDao.getByIds(it) }
            .orEmpty()
        return (byMeta + byContent)
            .map(WorkSessionEntity::toModel)
            .sortedByDescending(WorkSession::updatedAt)
    }

    fun observeMessages(sessionId: String): Flow<List<WorkMessage>> =
        messageDao.observeBySession(sessionId).map { list -> list.map(WorkMessageEntity::toModel) }

    fun observeMachines(): Flow<List<WorkMachine>> =
        machineDao.observeAll().map { list -> list.map(WorkMachineEntity::toModel) }

    fun observePresets(): Flow<List<RepoPreset>> =
        presetDao.observeAll().map { list -> list.map(WorkRepoPresetEntity::toModel) }

    suspend fun getPreset(id: String): RepoPreset? = presetDao.getById(id)?.toModel()

    suspend fun savePreset(preset: RepoPreset) =
        presetDao.upsert(WorkRepoPresetEntity.fromModel(preset))

    suspend fun deletePreset(id: String) = presetDao.deleteById(id)

    /** 用恢复密钥登录并完成首次快照同步；失败时抛协议层异常，由调用方翻译 */
    suspend fun connect(recoveryKey: String) {
        val credentials = authApi.exchangeRecoveryKey(recoveryKey)
        credentialsStore.save(credentials)
        connected.value = true
        refreshSnapshot()
    }

    suspend fun disconnect() {
        socketClient.disconnect()
        credentialsStore.clear()
        protocolSessions.clear()
        protocolMachines.clear()
        messageDao.clearAll()
        sessionDao.clearAll()
        machineDao.clearAll()
        connected.value = false
        syncError.value = null
    }

    /** 建立 Socket 长连接（幂等）；失败静默，HTTP 快照仍可用 */
    suspend fun ensureRealtime() {
        val credentials = credentialsStore.load() ?: return
        runCatching { socketClient.connect(credentials) }
    }

    suspend fun refreshSnapshot() {
        val credentials = credentialsStore.load() ?: run {
            connected.value = false
            return
        }
        snapshotMutex.withLock {
            syncing.value = true
            try {
                val snapshot = syncApi.fetchSnapshot(credentials)
                snapshot.machines.forEach { protocolMachines[it.id] = it }
                snapshot.sessions.forEach { protocolSessions[it.id] = it }
                machineDao.replaceAll(snapshot.machines.map { it.toWorkEntity() })
                // last_permission_mode 是本地维护的列，快照覆盖时需要保留
                val localModes = sessionDao.getPermissionModes()
                    .associate { it.id to it.lastPermissionMode }
                sessionDao.replaceAll(snapshot.sessions.map { session ->
                    session.toWorkEntity().copy(lastPermissionMode = localModes[session.id])
                })
                messageDao.deleteSessionsNotIn(snapshot.sessions.map(HappySession::id))
                syncError.value = null
            } catch (throwable: Throwable) {
                syncError.value = throwable
                throw throwable
            } finally {
                syncing.value = false
            }
        }
    }

    /** 增量同步某个会话的消息（after_seq 基于本地已缓存的最大 seq） */
    suspend fun syncMessages(sessionId: String) {
        val credentials = credentialsStore.load() ?: return
        val session = requireProtocolSession(sessionId)
        val afterSeq = messageDao.maxSeq(sessionId) ?: 0
        val records = syncApi.fetchMessages(credentials, session, afterSeq)
        if (records.isEmpty()) return
        var latestMode: Pair<Long, String>? = null
        val entities = records.mapNotNull { record ->
            val parsed = WorkMessageParser.parse(record.body) ?: return@mapNotNull null
            parsed.permissionMode?.let { mode ->
                if (latestMode == null || record.seq > latestMode!!.first) {
                    latestMode = record.seq to mode
                }
            }
            WorkMessageEntity.fromModel(
                WorkMessage(
                    id = record.id,
                    sessionId = sessionId,
                    seq = record.seq,
                    role = parsed.role,
                    parts = parsed.parts,
                    createdAt = record.createdAt,
                )
            )
        }
        if (entities.isNotEmpty()) messageDao.upsertAll(entities)
        // 其他客户端也可能切换过模式，用最新用户消息的 meta 回填会话级模式
        latestMode?.let { (_, mode) -> sessionDao.updatePermissionMode(sessionId, mode) }
    }

    /**
     * 发送补充指令。fullAccess 非空时显式下发执行模式（CLI 侧模式按会话粘滞），
     * 仓库级 disallowedTools 每条消息随行，保证策略始终由远端强制。
     */
    suspend fun sendMessage(
        sessionId: String,
        text: String,
        fullAccess: Boolean? = null,
        model: String? = null,
    ) {
        val credentials = requireCredentials()
        val session = requireProtocolSession(sessionId)
        val permissionMode = when (fullAccess) {
            true -> PERMISSION_MODE_FULL_ACCESS
            false -> "default"
            null -> null
        }
        syncApi.sendMessage(
            credentials = credentials,
            session = session,
            text = text,
            permissionMode = permissionMode,
            model = model,
            disallowedTools = findPresetFor(session)?.disallowedTools?.takeIf { it.isNotEmpty() },
        )
        permissionMode?.let { sessionDao.updatePermissionMode(sessionId, it) }
        runCatching { syncMessages(sessionId) }
    }

    /** 按 machineId + 归一化路径匹配会话所属的仓库预设 */
    suspend fun findPresetForSession(sessionId: String): RepoPreset? =
        protocolSessions[sessionId]?.let { findPresetFor(it) }

    private suspend fun findPresetFor(session: HappySession): RepoPreset? {
        val machineId = session.machineId ?: return null
        val path = session.path ?: return null
        return presetDao.getAll()
            .map(WorkRepoPresetEntity::toModel)
            .firstOrNull { preset ->
                preset.machineId == machineId && normalizePath(preset.path) == normalizePath(path)
            }
    }

    private fun normalizePath(path: String): String =
        path.trim().trimEnd('/', '\\').replace('\\', '/').lowercase()

    suspend fun abort(sessionId: String) {
        socketClient.abort(requireCredentials(), requireProtocolSession(sessionId))
    }

    suspend fun approve(sessionId: String, approvalId: String, forSession: Boolean) {
        socketClient.approve(requireCredentials(), requireProtocolSession(sessionId), approvalId, forSession)
        runCatching { refreshSnapshot() }
    }

    suspend fun deny(sessionId: String, approvalId: String, abort: Boolean) {
        socketClient.deny(requireCredentials(), requireProtocolSession(sessionId), approvalId, abort)
        runCatching { refreshSnapshot() }
    }

    /** 在指定机器与目录上启动新会话，成功后携带执行策略 meta 发送首条指令 */
    suspend fun spawnSession(request: WorkSpawnRequest): WorkSpawnOutcome {
        val credentials = requireCredentials()
        val machine = protocolMachines[request.machineId]
            ?: return WorkSpawnOutcome.Error("开发机信息尚未同步，请刷新后重试")
        if (!machine.active) return WorkSpawnOutcome.Error("所选开发机当前不在线")
        return when (val result = socketClient.spawnSession(
            credentials = credentials,
            machine = machine,
            directory = request.directory.trim(),
            agent = request.agent.wireName,
            approvedNewDirectoryCreation = request.approvedNewDirectoryCreation,
            environmentVariables = spawnEnvironment(request.agent, request.reasoningEffort),
        )) {
            is HappySpawnResult.Success -> {
                val session = awaitSession(result.sessionId)
                    ?: return WorkSpawnOutcome.Error("会话已启动，但同步尚未完成，请刷新后查看")
                request.prompt.trim().takeIf(String::isNotBlank)?.let { firstPrompt ->
                    syncApi.sendMessage(
                        credentials = credentials,
                        session = session,
                        text = firstPrompt,
                        permissionMode = if (request.fullAccess) PERMISSION_MODE_FULL_ACCESS else "default",
                        model = request.model?.takeIf(String::isNotBlank),
                        disallowedTools = request.disallowedTools.takeIf { it.isNotEmpty() },
                    )
                    sessionDao.updatePermissionMode(
                        id = session.id,
                        mode = if (request.fullAccess) PERMISSION_MODE_FULL_ACCESS else "default",
                    )
                }
                WorkSpawnOutcome.Success(session.id)
            }
            is HappySpawnResult.DirectoryApprovalRequired ->
                WorkSpawnOutcome.NeedsDirectoryApproval(result.directory)
            is HappySpawnResult.Error -> WorkSpawnOutcome.Error(result.message)
        }
    }

    /** 恢复已结束的会话；返回新的（或原有的）会话 ID */
    suspend fun resumeSession(sessionId: String): WorkSpawnOutcome {
        val credentials = requireCredentials()
        val session = requireProtocolSession(sessionId)
        val machine = session.machineId?.let(protocolMachines::get)
        if (machine == null || !machine.active) {
            return WorkSpawnOutcome.Error("原开发机当前离线，无法恢复此对话")
        }
        val agent = WorkAgent.detect(session.flavor, session.codexThreadId)
        return when (val result = socketClient.resumeSession(credentials, machine, session, agent.wireName)) {
            is HappySpawnResult.Success -> {
                awaitSession(result.sessionId)
                WorkSpawnOutcome.Success(result.sessionId)
            }
            is HappySpawnResult.DirectoryApprovalRequired ->
                WorkSpawnOutcome.NeedsDirectoryApproval(result.directory)
            is HappySpawnResult.Error -> WorkSpawnOutcome.Error(result.message)
        }
    }

    private suspend fun awaitSession(sessionId: String): HappySession? {
        repeat(SESSION_WAIT_ATTEMPTS) {
            runCatching { refreshSnapshot() }
            protocolSessions[sessionId]?.let { return it }
            delay(SESSION_WAIT_INTERVAL_MS)
        }
        return null
    }

    private suspend fun requireProtocolSession(sessionId: String): HappySession {
        protocolSessions[sessionId]?.let { return it }
        // 进程重启后内存缓存为空：先补一次快照
        runCatching { refreshSnapshot() }
        return protocolSessions[sessionId] ?: throw WorkSessionNotSyncedException(sessionId)
    }

    private fun requireCredentials(): HappyCredentials =
        credentialsStore.load() ?: run {
            connected.value = false
            throw WorkNotConnectedException()
        }

    private fun HappyMachine.toWorkEntity(): WorkMachineEntity = WorkMachineEntity(
        id = id,
        host = host,
        displayName = displayName,
        platform = platform,
        active = active,
        activeAt = activeAt,
        supportsCodex = supportsCodex,
        supportsClaude = supportsClaude,
        homeDir = homeDir,
    )

    private fun HappySession.toWorkEntity(): WorkSessionEntity = WorkSessionEntity.fromModel(
        WorkSession(
            id = id,
            machineId = machineId,
            path = path,
            host = host,
            name = name,
            agent = WorkAgent.detect(flavor, codexThreadId),
            active = active,
            activeAt = activeAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            approvals = approvals.map { WorkApproval(it.id, it.tool, it.arguments) },
            decryptable = encryptionKey != null,
        )
    )

    private companion object {
        const val SESSION_WAIT_ATTEMPTS = 10
        const val SESSION_WAIT_INTERVAL_MS = 1_000L
    }
}

sealed interface WorkSpawnOutcome {
    data class Success(val sessionId: String) : WorkSpawnOutcome
    data class NeedsDirectoryApproval(val directory: String) : WorkSpawnOutcome
    data class Error(val message: String) : WorkSpawnOutcome
}

class WorkNotConnectedException : Exception("Happy credentials missing")

class WorkSessionNotSyncedException(val sessionId: String) :
    Exception("Session $sessionId is not in the synced snapshot")

fun newRepoPresetId(): String = UUID.randomUUID().toString()
