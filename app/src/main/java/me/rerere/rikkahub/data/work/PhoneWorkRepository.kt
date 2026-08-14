package me.rerere.rikkahub.data.work

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.dao.PhoneWorkDAO
import me.rerere.rikkahub.data.db.entity.PhoneWorkEventEntity
import me.rerere.rikkahub.data.db.entity.PhoneWorkSessionEntity
import me.rerere.rikkahub.service.PhoneWorkTrackingService

class PhoneWorkRepository(
    private val dao: PhoneWorkDAO,
    private val api: PhoneWorkApiClient,
    val credentials: PhoneWorkCredentialStore,
    private val catalogStore: PhoneWorkCatalogStore,
    private val context: Context,
) : PhoneWorkSessionGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableCatalog = MutableStateFlow(catalogStore.load())
    private val mutableQueues = MutableStateFlow<Map<String, List<PhoneWorkQueueItem>>>(emptyMap())
    val catalog: StateFlow<PhoneWorkCatalog> = mutableCatalog

    fun observeSessions(): Flow<List<PhoneWorkSession>> = dao.observeSessions().map { rows -> rows.map { it.toModel() } }

    fun observeArchivedSessions(): Flow<List<PhoneWorkSession>> = dao.observeArchivedSessions().map { rows -> rows.map { it.toModel() } }

    fun observeActiveSessions(): Flow<List<PhoneWorkSession>> = dao.observeActiveSessions().map { rows -> rows.map { it.toModel() } }

    fun observeSession(id: String): Flow<PhoneWorkSession?> = dao.observeSession(id).map { it?.toModel() }

    fun observeEvents(id: String): Flow<List<PhoneWorkEvent>> = dao.observeEvents(id).map { rows ->
        rows.map { row ->
            PhoneWorkEvent(row.sessionId, row.seq, row.id, row.type, json.parseToJsonElement(row.payloadJson), row.createdAt)
        }
    }

    fun observeQueue(id: String): Flow<List<PhoneWorkQueueItem>> = mutableQueues
        .map { it[id].orEmpty() }
        .distinctUntilChanged()

    suspend fun refreshCatalog(): PhoneWorkCatalog {
        val runners = api.runners()
        val repos = runners.flatMap { runner -> runCatching { api.repos(runner.id) }.getOrDefault(emptyList()) }
        return PhoneWorkCatalog(runners, repos, System.currentTimeMillis()).also {
            catalogStore.save(it)
            mutableCatalog.value = it
        }
    }

    suspend fun refreshSessions() {
        val sessions = api.sessions() + api.sessions(archived = true)
        dao.replaceSessions(sessions.distinctBy { it.id }.map { it.toEntity() })
    }

    suspend fun refreshEvents(sessionId: String): List<PhoneWorkEvent> {
        val events = api.events(sessionId, dao.maxSeq(sessionId))
        if (events.isNotEmpty()) dao.upsertEvents(events.map { it.toEntity() })
        refreshSessions()
        return events
    }

    suspend fun refreshQueue(sessionId: String): List<PhoneWorkQueueItem> = api.queue(sessionId).also { items ->
        mutableQueues.value = mutableQueues.value + (sessionId to items)
    }

    suspend fun activeSessionsSnapshot(): List<PhoneWorkSession> = observeActiveSessions().first()
    suspend fun sessionSnapshot(id: String): PhoneWorkSession? = observeSession(id).first()
    suspend fun maxEventSeq(id: String): Long = dao.maxSeq(id)
    suspend fun cachedEventsAfter(id: String, afterSeq: Long): List<PhoneWorkEvent> = dao.eventsAfter(id, afterSeq).map { row ->
        PhoneWorkEvent(row.sessionId, row.seq, row.id, row.type, json.parseToJsonElement(row.payloadJson), row.createdAt)
    }

    fun liveEvents(sessionId: String): Flow<Unit> = flow {
        val cursor = dao.maxSeq(sessionId)
        api.eventStream(sessionId, cursor).collect { update ->
            when (update) {
                PhoneWorkStreamUpdate.Connected -> emit(Unit)
                is PhoneWorkStreamUpdate.Event -> {
                    dao.upsertEvents(listOf(update.event.toEntity()))
                    emit(Unit)
                }
            }
        }
    }

    override suspend fun createSession(
        request: CreateSessionRequest,
        attachments: List<PhoneWorkPendingAttachment>,
    ): PhoneWorkSession {
        val attachmentIds = uploadAttachments(attachments)
        val session = api.createSession(request.copy(attachmentIds = attachmentIds))
        dao.upsertSession(session.toEntity())
        refreshEvents(session.id)
        PhoneWorkTrackingService.start(context)
        return session
    }

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        attachments: List<PhoneWorkPendingAttachment> = emptyList(),
        reasoningEffort: String? = null,
        fastMode: Boolean? = null,
    ) {
        val attachmentIds = uploadAttachments(attachments)
        val event = api.sendMessage(
            sessionId = sessionId,
            text = text,
            attachmentIds = attachmentIds,
            reasoningEffort = reasoningEffort,
            fastMode = fastMode,
        )
        dao.upsertEvents(listOf(event.toEntity()))
        refreshSessions()
        PhoneWorkTrackingService.start(context)
    }

    suspend fun enqueue(
        sessionId: String,
        text: String,
        attachments: List<PhoneWorkPendingAttachment> = emptyList(),
        reasoningEffort: String? = null,
        fastMode: Boolean? = null,
    ) {
        val attachmentIds = uploadAttachments(attachments)
        val item = api.enqueue(
            sessionId = sessionId,
            text = text,
            attachmentIds = attachmentIds,
            reasoningEffort = reasoningEffort,
            fastMode = fastMode,
        )
        mutableQueues.value = mutableQueues.value + (
            sessionId to (mutableQueues.value[sessionId].orEmpty().filterNot { it.id == item.id } + item)
                .sortedBy { it.createdAt }
        )
        refreshSessions()
        PhoneWorkTrackingService.start(context)
    }

    suspend fun updateQueueItem(sessionId: String, itemId: String, revision: Int, text: String) {
        val updated = api.updateQueueItem(sessionId, itemId, revision, text)
        mutableQueues.value = mutableQueues.value + (
            sessionId to mutableQueues.value[sessionId].orEmpty().map { item ->
                if (item.id == itemId) updated else item
            }
        )
    }

    suspend fun cancelQueueItem(sessionId: String, itemId: String, revision: Int) {
        api.cancelQueueItem(sessionId, itemId, revision)
        mutableQueues.value = mutableQueues.value + (
            sessionId to mutableQueues.value[sessionId].orEmpty().filterNot { it.id == itemId }
        )
    }

    suspend fun steer(
        sessionId: String,
        expectedTurnId: String,
        text: String,
        attachments: List<PhoneWorkPendingAttachment> = emptyList(),
    ) {
        val attachmentIds = uploadAttachments(attachments)
        api.steer(
            sessionId = sessionId,
            expectedTurnId = expectedTurnId,
            text = text,
            attachmentIds = attachmentIds,
        )
        PhoneWorkTrackingService.start(context)
    }

    private suspend fun uploadAttachments(attachments: List<PhoneWorkPendingAttachment>): List<String> {
        require(attachments.size <= 4) { "每条 Work 消息最多发送 4 个附件" }
        return attachments.map { api.uploadAttachment(it).id }
    }

    suspend fun answer(sessionId: String, askId: String, answers: List<PhoneWorkAnswer>) {
        api.answer(sessionId, askId, answers)
        refreshEvents(sessionId)
    }

    suspend fun stop(sessionId: String) = api.stop(sessionId)
    suspend fun complete(sessionId: String) {
        api.complete(sessionId)
        refreshEvents(sessionId)
    }
    suspend fun archive(sessionId: String) = dao.upsertSession(api.archive(sessionId).toEntity())
    suspend fun unarchive(sessionId: String) = dao.upsertSession(api.unarchive(sessionId).toEntity())
    suspend fun reportHtml(reportId: String): String = api.reportHtml(reportId)

    private fun PhoneWorkSession.toEntity() = PhoneWorkSessionEntity(
        id = id,
        runnerId = runnerId,
        repoId = repoId,
        repoName = repoName,
        title = title,
        runtime = runtime,
        model = model,
        reasoningEffort = reasoningEffort,
        fastMode = fastMode,
        status = status,
        runtimeSessionId = runtimeSessionId,
        codexSessionId = codexSessionId,
        activeTurnId = activeTurnId,
        lastSeq = lastSeq,
        archivedAt = archivedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun PhoneWorkSessionEntity.toModel() = PhoneWorkSession(
        id = id,
        runnerId = runnerId,
        repoId = repoId,
        repoName = repoName,
        title = title,
        runtime = runtime,
        model = model,
        reasoningEffort = reasoningEffort,
        fastMode = fastMode,
        status = status,
        runtimeSessionId = runtimeSessionId,
        codexSessionId = codexSessionId,
        activeTurnId = activeTurnId,
        lastSeq = lastSeq,
        archivedAt = archivedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun PhoneWorkEvent.toEntity() = PhoneWorkEventEntity(
        sessionId, seq, id, type, json.encodeToString(payload), createdAt,
    )
}
