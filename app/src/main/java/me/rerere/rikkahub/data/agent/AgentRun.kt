package me.rerere.rikkahub.data.agent

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "agent_runs", indices = [Index(value = ["parentConversationId", "requestId"], unique = true)])
data class AgentRun(
    @PrimaryKey val id: String,
    val parentConversationId: String,
    val requestId: String,
    val agentId: String,
    val assistantJson: String,
    val status: String = "RUNNING",
    val revision: Long = 0,
    val lastCommandId: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface AgentRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: AgentRun)

    @Query("SELECT * FROM agent_runs WHERE id = :id")
    suspend fun get(id: String): AgentRun?

    @Query("SELECT * FROM agent_runs WHERE parentConversationId = :parent AND requestId = :request")
    suspend fun findRequest(parent: String, request: String): AgentRun?

    @Query("SELECT * FROM agent_runs WHERE parentConversationId = :parent ORDER BY status = 'RUNNING' DESC, updatedAt DESC LIMIT 50")
    suspend fun list(parent: String): List<AgentRun>

    @Query("SELECT * FROM agent_runs WHERE id = :id")
    fun observe(id: String): Flow<AgentRun?>

    @Query("UPDATE agent_runs SET status = :status, revision = revision + 1, updatedAt = :now WHERE id = :id AND status = 'RUNNING'")
    suspend fun finish(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE agent_runs SET status = 'FAILED', revision = revision + 1 WHERE id = :id AND status = 'RUNNING' AND revision = :revision")
    suspend fun failIfRunning(id: String, revision: Long)

    @Query("UPDATE agent_runs SET status = 'STOPPED', revision = revision + 1 WHERE id = :id AND status IN ('RUNNING', 'WAITING_FOR_INPUT', 'INTERRUPTED', 'FAILED')")
    suspend fun stop(id: String)

    @Query("UPDATE agent_runs SET status = 'INTERRUPTED', revision = revision + 1 WHERE status = 'RUNNING'")
    suspend fun recover()

    @Query("UPDATE agent_runs SET status = 'RUNNING', revision = revision + 1, lastCommandId = :command, updatedAt = :now WHERE id = :id AND revision = :revision AND status != 'RUNNING'")
    suspend fun resume(id: String, revision: Long, command: String, now: Long = System.currentTimeMillis()): Int
}
