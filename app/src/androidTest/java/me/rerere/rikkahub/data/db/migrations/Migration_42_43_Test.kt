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
class Migration_42_43_Test {
    private val testDb = "assistant-task-migration-42-43"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun preservesExistingAgendaDataAndCreatesEmptyTaskTables() {
        helper.createDatabase(testDb, 42).apply {
            insert("agenda_tasks", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
                put("id", "agenda-1")
                put("title", "原有安排")
                put("note", "")
                put("status", "PENDING")
                put("source", "MANUAL")
                put("created_at", 1L)
                put("updated_at", 1L)
            })
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 43, true, Migration_42_43)
        db.query("SELECT title FROM agenda_tasks WHERE id = 'agenda-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("原有安排", cursor.getString(0))
        }
        listOf(
            "assistant_tasks",
            "assistant_task_events",
            "assistant_task_links",
            "assistant_runtime_contexts",
        ).forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        db.close()
    }
}
