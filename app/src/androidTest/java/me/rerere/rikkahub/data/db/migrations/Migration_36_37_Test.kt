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
class Migration_36_37_Test {
    private val testDb = "work-session-title-migration-36-37"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun backfillsExistingSessionTitleFromRepositoryName() {
        helper.createDatabase(testDb, 36).apply {
            insert(
                "phone_work_sessions",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "work-1")
                    put("runner_id", "runner-1")
                    put("repo_id", "zhixing")
                    put("repo_name", "zhixing-rikkahub")
                    put("model", "gpt-5.6-sol")
                    put("reasoning_effort", "high")
                    put("status", "IDLE")
                    put("last_seq", 0L)
                    put("created_at", "2026-07-21T00:00:00Z")
                    put("updated_at", "2026-07-21T00:00:00Z")
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 37, true, Migration_36_37)
        val session = db.query("SELECT title FROM phone_work_sessions WHERE id = 'work-1'")
        assertTrue(session.moveToFirst())
        assertEquals("zhixing-rikkahub", session.getString(session.getColumnIndexOrThrow("title")))
        session.close()
        db.close()
    }
}
