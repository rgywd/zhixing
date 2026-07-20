package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.PhoneWorkEventEntity
import me.rerere.rikkahub.data.db.entity.PhoneWorkSessionEntity

@Dao
interface PhoneWorkDAO {
    @Query("SELECT * FROM phone_work_sessions ORDER BY updated_at DESC")
    fun observeSessions(): Flow<List<PhoneWorkSessionEntity>>

    @Query("SELECT * FROM phone_work_sessions WHERE id = :id")
    fun observeSession(id: String): Flow<PhoneWorkSessionEntity?>

    @Upsert
    suspend fun upsertSessions(sessions: List<PhoneWorkSessionEntity>)

    @Upsert
    suspend fun upsertSession(session: PhoneWorkSessionEntity)

    @Query("SELECT * FROM phone_work_events WHERE session_id = :sessionId ORDER BY seq")
    fun observeEvents(sessionId: String): Flow<List<PhoneWorkEventEntity>>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM phone_work_events WHERE session_id = :sessionId")
    suspend fun maxSeq(sessionId: String): Long

    @Upsert
    suspend fun upsertEvents(events: List<PhoneWorkEventEntity>)
}
