package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.PhoneWorkEventEntity
import me.rerere.rikkahub.data.db.entity.PhoneWorkSessionEntity

@Dao
interface PhoneWorkDAO {
    @Query("SELECT * FROM phone_work_sessions WHERE archived_at IS NULL ORDER BY updated_at DESC")
    fun observeSessions(): Flow<List<PhoneWorkSessionEntity>>

    @Query("SELECT * FROM phone_work_sessions WHERE archived_at IS NOT NULL ORDER BY archived_at DESC")
    fun observeArchivedSessions(): Flow<List<PhoneWorkSessionEntity>>

    @Query("SELECT * FROM phone_work_sessions WHERE archived_at IS NULL AND status IN ('QUEUED', 'RUNNING', 'WAITING_FOR_USER') ORDER BY updated_at DESC")
    fun observeActiveSessions(): Flow<List<PhoneWorkSessionEntity>>

    @Query("SELECT * FROM phone_work_sessions WHERE id = :id")
    fun observeSession(id: String): Flow<PhoneWorkSessionEntity?>

    @Upsert
    suspend fun upsertSessions(sessions: List<PhoneWorkSessionEntity>)

    @Query("DELETE FROM phone_work_sessions")
    suspend fun deleteAllSessions()

    @Transaction
    suspend fun replaceSessions(sessions: List<PhoneWorkSessionEntity>) {
        deleteAllSessions()
        upsertSessions(sessions)
    }

    @Upsert
    suspend fun upsertSession(session: PhoneWorkSessionEntity)

    @Query("SELECT * FROM phone_work_events WHERE session_id = :sessionId ORDER BY seq")
    fun observeEvents(sessionId: String): Flow<List<PhoneWorkEventEntity>>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM phone_work_events WHERE session_id = :sessionId")
    suspend fun maxSeq(sessionId: String): Long

    @Query("SELECT * FROM phone_work_events WHERE session_id = :sessionId AND seq > :afterSeq ORDER BY seq")
    suspend fun eventsAfter(sessionId: String, afterSeq: Long): List<PhoneWorkEventEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM phone_work_events WHERE session_id = :sessionId")
    fun observeMaxSeq(sessionId: String): Flow<Long>

    @Upsert
    suspend fun upsertEvents(events: List<PhoneWorkEventEntity>)
}
