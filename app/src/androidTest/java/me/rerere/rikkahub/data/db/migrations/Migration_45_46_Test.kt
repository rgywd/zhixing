package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_45_46_Test {
    private val testDb = "assistant-task-cleanup-migration-45-46"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun clearsAssistantTaskHistoryAndPreservesConversationAndRuntimeContext() {
        helper.createDatabase(testDb, 45).apply {
            insert(
                "ConversationEntity",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "conversation-1")
                    put("title", "保留的聊天")
                    put("nodes", "[]")
                    put("create_at", 1L)
                    put("update_at", 2L)
                },
            )
            execSQL(
                """
                INSERT INTO assistant_tasks(
                    id, title, status, attempt, conversation_id, anchor_message_id, anchor_node_id,
                    summary, result_kind, result_ref, error_code, attention_reason,
                    created_at, updated_at, started_at, finished_at
                ) VALUES (
                    'task-1', '旧失败', 'FAILED_RETRYABLE', 1, 'conversation-1', 'message-1', 'node-1',
                    '写入失败', NULL, NULL, 'WRITE_FAILED', '可重试', 1, 2, 1, 2
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO assistant_task_events(
                    task_id, seq, type, message, result_ref, error_code, idempotency_key, created_at
                ) VALUES ('task-1', 1, 'FAILED', '写入失败', NULL, 'WRITE_FAILED', NULL, 2)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO assistant_task_links(task_id, object_type, object_id, role, created_at)
                VALUES ('task-1', 'CONVERSATION', 'conversation-1', 'SOURCE', 1)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO assistant_runtime_contexts(
                    id, conversation_id, message_id, kind, title, summary, recommendation,
                    generated_at, valid_until, privacy_scope_json, evidence_json, created_at
                ) VALUES (
                    'context-1', 'conversation-1', 'message-1', 'STATUS', '状态', '摘要', NULL,
                    1, 2, '[]', '[]', 1
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 46, true, Migration_45_46)

        assertEquals(0, db.count("assistant_tasks"))
        assertEquals(0, db.count("assistant_task_events"))
        assertEquals(0, db.count("assistant_task_links"))
        assertEquals(1, db.count("assistant_runtime_contexts"))
        db.query("SELECT title FROM ConversationEntity WHERE id = 'conversation-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("保留的聊天", cursor.getString(0))
        }
        db.close()
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
