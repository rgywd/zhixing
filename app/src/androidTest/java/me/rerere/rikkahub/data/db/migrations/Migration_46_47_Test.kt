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
class Migration_46_47_Test {
    private val testDb = "phone-work-fast-mode-migration-46-47"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun addsStandardSpeedToExistingWorkSessions() {
        helper.createDatabase(testDb, 46).apply {
            insert(
                "phone_work_sessions",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "work-1")
                    put("runner_id", "runner-1")
                    put("repo_id", "repo-1")
                    put("repo_name", "zhixing")
                    put("title", "旧会话")
                    put("runtime", "codex")
                    put("model", "gpt-5.6-sol")
                    put("reasoning_effort", "high")
                    put("status", "IDLE")
                    putNull("runtime_session_id")
                    putNull("codex_session_id")
                    put("last_seq", 0L)
                    putNull("archived_at")
                    put("created_at", "2026-08-14T00:00:00Z")
                    put("updated_at", "2026-08-14T00:00:00Z")
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 47, true, Migration_46_47)
        db.query("SELECT fast_mode FROM phone_work_sessions WHERE id = 'work-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }
}
