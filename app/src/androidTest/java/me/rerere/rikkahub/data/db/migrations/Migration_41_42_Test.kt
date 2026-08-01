package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_41_42_Test {
    private val testDb = "agenda-recurrence-migration-41-42"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun preservesExistingTasksAndAddsNullableRecurrence() {
        helper.createDatabase(testDb, 41).apply {
            insert("agenda_tasks", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
                put("id", "task-1")
                put("title", "原有待办")
                put("note", "")
                put("status", "PENDING")
                put("source", "MANUAL")
                put("created_at", 1L)
                put("updated_at", 1L)
            })
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 42, true, Migration_41_42)
        db.query(
            "SELECT title, recurrence_frequency, recurrence_interval FROM agenda_tasks WHERE id = 'task-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("原有待办", cursor.getString(0))
            assertNull(cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
        }
        db.close()
    }
}
