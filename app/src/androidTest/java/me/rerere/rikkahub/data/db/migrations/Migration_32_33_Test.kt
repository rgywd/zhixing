package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_32_33_Test {
    private val testDb = "retired-work-tables-migration-32-33"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun removesRetiredWorkTablesAndKeepsPhoneLineTables() {
        helper.createDatabase(testDb, 32).close()

        val db = helper.runMigrationsAndValidate(testDb, 33, true, Migration_32_33)
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        assertFalse("work_machines" in tables)
        assertFalse("work_sessions" in tables)
        assertFalse("codex_threads" in tables)
        assertFalse("codex_runtime_catalogs" in tables)
        assertTrue("phone_work_sessions" in tables)
        assertTrue("phone_work_events" in tables)
        db.close()
    }
}
