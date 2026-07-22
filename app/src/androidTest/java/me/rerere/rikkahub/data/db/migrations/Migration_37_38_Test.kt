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
class Migration_37_38_Test {
    private val testDb = "agenda-migration-37-38"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun createsAgendaTableAndPersistsTask() {
        helper.createDatabase(testDb, 37).close()
        val db = helper.runMigrationsAndValidate(testDb, 38, true, Migration_37_38)
        db.insert("agenda_tasks", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
            put("id", "task-1")
            put("title", "迁移后的待办")
            put("note", "")
            put("status", "PENDING")
            put("source", "MANUAL")
            put("created_at", 1L)
            put("updated_at", 1L)
        })

        val cursor = db.query("SELECT * FROM agenda_tasks WHERE id = 'task-1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("迁移后的待办", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        cursor.close()
        db.close()
    }
}
