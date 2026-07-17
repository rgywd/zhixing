package me.rerere.rikkahub.data.db.fts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.workflow.WorkMessage
import me.rerere.rikkahub.data.workflow.WorkMessagePart

/**
 * 远程工作会话消息的全文检索，与主页聊天共用 simple/jieba FTS5 管线。
 * 虚表 work_message_fts 在 DataSourceModule 的 onOpen 回调中创建。
 */
class WorkFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexMessages(messages: List<WorkMessage>) = withContext(Dispatchers.IO) {
        messages.forEach { message ->
            val text = message.extractFtsText()
            db.execSQL("DELETE FROM work_message_fts WHERE message_id = ?", arrayOf(message.id))
            if (text.isNotBlank()) {
                db.execSQL(
                    "INSERT INTO work_message_fts(text, message_id, session_id) VALUES (?, ?, ?)",
                    arrayOf(text, message.id, message.sessionId),
                )
            }
        }
    }

    suspend fun retainSessions(sessionIds: List<String>) = withContext(Dispatchers.IO) {
        if (sessionIds.isEmpty()) {
            db.execSQL("DELETE FROM work_message_fts")
            return@withContext
        }
        val placeholders = sessionIds.joinToString(",") { "?" }
        db.execSQL(
            "DELETE FROM work_message_fts WHERE session_id NOT IN ($placeholders)",
            sessionIds.toTypedArray(),
        )
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM work_message_fts")
    }

    suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) {
        db.query("SELECT COUNT(*) FROM work_message_fts").use { cursor ->
            cursor.moveToFirst() && cursor.getLong(0) == 0L
        }
    }

    /** 返回内容命中的会话 ID（按相关度） */
    suspend fun searchSessionIds(keyword: String): List<String> = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        runCatching {
            db.query(
                """
                SELECT DISTINCT session_id FROM work_message_fts
                WHERE text MATCH jieba_query(?)
                ORDER BY rank
                LIMIT 100
                """.trimIndent(),
                arrayOf(keyword),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    results += cursor.getString(0)
                }
            }
        }
        results
    }
}

private fun WorkMessage.extractFtsText(): String = parts.joinToString("\n") { part ->
    when (part) {
        is WorkMessagePart.Text -> part.text
        is WorkMessagePart.Reasoning -> part.text
        is WorkMessagePart.ToolCall -> part.title ?: part.name
        is WorkMessagePart.Raw -> part.text
        else -> ""
    }
}.trim()
