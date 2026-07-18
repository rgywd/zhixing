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
class Migration_27_28_Test {
    private val testDb = "codex-catalog-migration-27-28"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun addsCodexCatalogWithoutChangingLegacyWorkData() {
        helper.createDatabase(testDb, 27).apply {
            insert(
                "work_machines",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "happy-machine")
                    put("host", "legacy-host")
                    put("active", 1)
                    put("active_at", 123L)
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 28, true)
        db.query("SELECT host FROM work_machines WHERE id = 'happy-machine'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-host", cursor.getString(0))
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'codex_%'").use { cursor ->
            val tables = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertTrue("codex_projects" in tables)
            assertTrue("codex_threads" in tables)
            assertTrue("codex_turns" in tables)
            assertTrue("codex_items" in tables)
            assertTrue("codex_catalog_sync" in tables)
            assertTrue("codex_catalog_chunks" in tables)
            assertTrue("codex_tombstones" in tables)
        }
        db.close()
    }
}
