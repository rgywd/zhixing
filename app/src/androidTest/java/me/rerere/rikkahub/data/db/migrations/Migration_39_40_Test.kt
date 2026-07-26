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
class Migration_39_40_Test {
    private val testDb = "work-runtime-migration-39-40"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun existingCodexSessionsGainGenericRuntimeIdentity() {
        helper.createDatabase(testDb, 39).apply {
            insert("phone_work_sessions", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
                put("id", "work-1")
                put("runner_id", "runner-1")
                put("repo_id", "repo-1")
                put("repo_name", "zhixing")
                put("title", "现有会话")
                put("model", "gpt-5.6-sol")
                put("reasoning_effort", "high")
                put("status", "IDLE")
                put("codex_session_id", "codex-session-1")
                put("last_seq", 1L)
                put("created_at", "2026-07-26T00:00:00Z")
                put("updated_at", "2026-07-26T00:00:00Z")
            })
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 40, true, Migration_39_40)
        db.query(
            "SELECT runtime, runtime_session_id, codex_session_id FROM phone_work_sessions WHERE id='work-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("codex", cursor.getString(0))
            assertEquals("codex-session-1", cursor.getString(1))
            assertEquals("codex-session-1", cursor.getString(2))
        }
        db.close()
    }
}
