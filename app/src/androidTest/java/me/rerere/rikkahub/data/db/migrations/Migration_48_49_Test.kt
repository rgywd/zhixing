package me.rerere.rikkahub.data.db.migrations

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
class Migration_48_49_Test {
    private val testDb = "health-metrics-migration-48-49"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun createsStructuredHealthMetricRecords() {
        helper.createDatabase(testDb, 48).close()

        val db = helper.runMigrationsAndValidate(testDb, 49, true, Migration_48_49)
        db.execSQL(
            """
            INSERT INTO health_metric_records(
                id, metric_type, value_decimal, unit, observed_at_epoch_ms,
                recorded_at_epoch_ms, effective_at_epoch_ms, source_type,
                source_conversation_id, source_message_id
            ) VALUES(
                'health-1', 'WEIGHT_KG', '72.4', 'kg', NULL,
                1800000000000, 1800000000000, 'AI_EXTRACTED_CHAT',
                'conversation-1', 'message-1'
            )
            """.trimIndent(),
        )
        db.query(
            "SELECT value_decimal, observed_at_epoch_ms FROM health_metric_records WHERE id = 'health-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("72.4", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        db.close()
    }
}
