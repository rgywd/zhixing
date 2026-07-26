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
class Migration_40_41_Test {
    private val testDb = "monthly-ledger-migration-40-41"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun preservesExistingDataAndAddsMonthlyLedgerTables() {
        helper.createDatabase(testDb, 40).apply {
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

        val db = helper.runMigrationsAndValidate(testDb, 41, true, Migration_40_41)
        db.query("SELECT title FROM agenda_tasks WHERE id = 'task-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("原有待办", cursor.getString(0))
        }

        db.insert("monthly_ledger_summaries", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
            put("id", "2026-07|CNY")
            put("month", "2026-07")
            put("currency", "CNY")
            put("minor_unit", 2)
            put("expense_minor", 420_000L)
            put("income_minor", 20_000L)
            put("aggregation_mode", "DEDUPED_ESTIMATE")
            put("coverage", "PARTIAL")
            put("confidence_percent", 88)
            put("warnings_json", """["信用卡与支付平台可能重复"]""")
            put("updated_at", 1L)
        })
        db.insert("monthly_ledger_channels", SQLiteDatabase.CONFLICT_NONE, ContentValues().apply {
            put("summary_id", "2026-07|CNY")
            put("channel_key", "alipay")
            put("channel_name", "支付宝")
            put("expense_minor", 300_000L)
            put("income_minor", 10_000L)
            put("coverage", "FULL")
            put("confidence_percent", 95)
            put("warnings_json", "[]")
        })

        db.query(
            "SELECT expense_minor FROM monthly_ledger_channels " +
                "WHERE summary_id = '2026-07|CNY' AND channel_key = 'alipay'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(300_000L, cursor.getLong(0))
        }

        db.execSQL("PRAGMA foreign_keys = ON")
        db.delete(
            "monthly_ledger_summaries",
            "id = ?",
            arrayOf("2026-07|CNY"),
        )
        db.query(
            "SELECT COUNT(*) FROM monthly_ledger_channels WHERE summary_id = '2026-07|CNY'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }
}
