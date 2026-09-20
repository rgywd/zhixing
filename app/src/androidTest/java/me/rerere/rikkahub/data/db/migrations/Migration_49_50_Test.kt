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
class Migration_49_50_Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun addsDurableRunsAndPreservesExistingHealthData() {
        val name = "agent-runtime-migration"
        helper.createDatabase(name, 49).apply {
            execSQL("""INSERT INTO health_metric_records(
                id, metric_type, value_decimal, unit, observed_at_epoch_ms,
                recorded_at_epoch_ms, effective_at_epoch_ms, source_type,
                source_conversation_id, source_message_id
            ) VALUES ('health-1','WEIGHT_KG','72.4','kg',NULL,1,1,'AI_EXTRACTED_CHAT','c','m')""")
            close()
        }
        helper.runMigrationsAndValidate(name, 50, true).apply {
            query("SELECT value_decimal FROM health_metric_records WHERE id='health-1'").use {
                assertTrue(it.moveToFirst()); assertEquals("72.4", it.getString(0))
            }
            execSQL("INSERT INTO agent_runs VALUES ('run','parent','request','agent','{}','RUNNING',0,'',1)")
            query("SELECT status FROM agent_runs WHERE id='run'").use {
                assertTrue(it.moveToFirst()); assertEquals("RUNNING", it.getString(0))
            }
            close()
        }
    }
}
