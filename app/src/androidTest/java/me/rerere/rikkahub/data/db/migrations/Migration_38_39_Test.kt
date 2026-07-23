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
class Migration_38_39_Test {
    private val testDb = "agenda-migration-38-39"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun preservesSimpleTasksAndAddsPlanStages() {
        helper.createDatabase(testDb, 38).apply {
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

        val db = helper.runMigrationsAndValidate(testDb, 39, true, Migration_38_39)
        db.query("SELECT title FROM agenda_tasks WHERE id = 'task-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("原有待办", cursor.getString(0))
        }
        db.insert("agenda_plans", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
            put("id", "plan-1")
            put("title", "公司年会")
            put("note", "")
            put("location", "上海")
            put("status", "ACTIVE")
            put("source", "MANUAL")
            put("created_at", 1L)
            put("updated_at", 1L)
        })
        db.insert("agenda_plan_stages", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
            put("id", "stage-1")
            put("plan_id", "plan-1")
            put("title", "购买车票")
            put("note", "")
            put("status", "PENDING")
            put("position", 0)
            put("trigger_offset_minutes", -21600L)
            put("created_at", 1L)
            put("updated_at", 1L)
        })

        db.query("SELECT title, trigger_offset_minutes FROM agenda_plan_stages WHERE id = 'stage-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("购买车票", cursor.getString(0))
            assertEquals(-21600L, cursor.getLong(1))
        }
        db.close()
    }
}
