package me.rerere.rikkahub.data.work

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.dao.PhoneWorkDAO
import me.rerere.rikkahub.data.db.entity.PhoneWorkEventEntity
import me.rerere.rikkahub.data.db.entity.PhoneWorkSessionEntity

class PhoneWorkRepository(
    private val dao: PhoneWorkDAO,
    private val api: PhoneWorkApiClient,
    val credentials: PhoneWorkCredentialStore,
    private val catalogStore: PhoneWorkCatalogStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableCatalog = MutableStateFlow(catalogStore.load())
    val catalog: StateFlow<PhoneWorkCatalog> = mutableCatalog

    fun observeSessions(): Flow<List<PhoneWorkSession>> = dao.observeSessions().map { rows -> rows.map { it.toModel() } }

    fun observeSession(id: String): Flow<PhoneWorkSession?> = dao.observeSession(id).map { it?.toModel() }

    fun observeEvents(id: String): Flow<List<PhoneWorkEvent>> = dao.observeEvents(id).map { rows ->
        rows.map { row ->
            PhoneWorkEvent(row.sessionId, row.seq, row.id, row.type, json.parseToJsonElement(row.payloadJson), row.createdAt)
        }
    }

    suspend fun refreshCatalog(): PhoneWorkCatalog {
        val runners = api.runners()
        val repos = runners.flatMap { runner -> runCatching { api.repos(runner.id) }.getOrDefault(emptyList()) }
        return PhoneWorkCatalog(runners, repos, System.currentTimeMillis()).also {
            catalogStore.save(it)
            mutableCatalog.value = it
        }
    }

    suspend fun refreshSessions() {
        dao.upsertSessions(api.sessions().map { it.toEntity() })
    }

    suspend fun refreshEvents(sessionId: String) {
        val events = api.events(sessionId, dao.maxSeq(sessionId))
        if (events.isNotEmpty()) dao.upsertEvents(events.map { it.toEntity() })
        refreshSessions()
    }

    fun liveEvents(sessionId: String): Flow<Unit> = flow {
        val cursor = dao.maxSeq(sessionId)
        api.eventStream(sessionId, cursor).collect { event ->
            dao.upsertEvents(listOf(event.toEntity()))
            emit(Unit)
        }
    }

    suspend fun createSession(request: CreateSessionRequest): PhoneWorkSession {
        val session = api.createSession(request)
        dao.upsertSession(session.toEntity())
        refreshEvents(session.id)
        return session
    }

    suspend fun sendMessage(sessionId: String, text: String) {
        dao.upsertEvents(listOf(api.sendMessage(sessionId, text).toEntity()))
        refreshSessions()
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
    suspend fun reportHtml(reportId: String): String = api.reportHtml(reportId)

    private fun PhoneWorkSession.toEntity() = PhoneWorkSessionEntity(
        id, runnerId, repoId, repoName, model, reasoningEffort, status, codexSessionId, lastSeq, createdAt, updatedAt,
    )

    private fun PhoneWorkSessionEntity.toModel() = PhoneWorkSession(
        id, runnerId, repoId, repoName, model, reasoningEffort, status = status,
        codexSessionId = codexSessionId, lastSeq = lastSeq, createdAt = createdAt, updatedAt = updatedAt,
    )

    private fun PhoneWorkEvent.toEntity() = PhoneWorkEventEntity(
        sessionId, seq, id, type, json.encodeToString(payload), createdAt,
    )
}
