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
class Migration_26_27_Test {
    private val testDb = "memory-migration-26-27"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun legacyMemoryBecomesActiveContextWithoutLosingItsIdentityOrContent() {
        helper.createDatabase(testDb, 26).apply {
            insert(
                "MemoryEntity",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", 42)
                    put("assistant_id", "legacy-assistant")
                    put("content", "Legacy memory content")
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 27, true)
        val cursor = db.query("SELECT * FROM MemoryEntity WHERE id = 42")

        assertTrue(cursor.moveToFirst())
        assertEquals("legacy-assistant", cursor.getString(cursor.getColumnIndexOrThrow("assistant_id")))
        assertEquals("Legacy memory content", cursor.getString(cursor.getColumnIndexOrThrow("content")))
        assertEquals("CONTEXT", cursor.getString(cursor.getColumnIndexOrThrow("kind")))
        assertEquals("ACTIVE", cursor.getString(cursor.getColumnIndexOrThrow("state")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("created_at")))
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))

        cursor.close()
        db.close()
    }
}
