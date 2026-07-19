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
class Migration_30_31_Test {
    private val testDb = "codex-thread-detail-revision-migration-30-31"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun addsThreadDetailRevisionWithoutChangingExistingData() {
        helper.createDatabase(testDb, 30).apply {
            insert(
                "work_machines",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "existing-machine")
                    put("host", "existing-host")
                    put("active", 1)
                    put("active_at", 123L)
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 31, true)
        db.query("SELECT host FROM work_machines WHERE id = 'existing-machine'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("existing-host", cursor.getString(0))
        }
        db.query(
            "SELECT name FROM sqlite_master " +
                "WHERE type='table' AND name='codex_thread_detail_revisions'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        db.execSQL(
            "INSERT INTO codex_thread_detail_revisions " +
                "(machine_id, thread_id, revision, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any>("existing-machine", "thread-1", 7L, 456L),
        )
        db.query(
            "SELECT revision FROM codex_thread_detail_revisions " +
                "WHERE machine_id='existing-machine' AND thread_id='thread-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7L, cursor.getLong(0))
        }
        db.close()
    }
}
