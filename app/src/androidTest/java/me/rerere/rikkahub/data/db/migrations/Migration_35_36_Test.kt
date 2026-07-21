package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_35_36_Test {
    private val testDb = "profile-pipeline-migration-35-36"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun clearsLegacyProfilesAndKeepsContextMemories() {
        helper.createDatabase(testDb, 35).apply {
            insert(
                "MemoryEntity",
                SQLiteDatabase.CONFLICT_NONE,
                memory(id = 1, kind = "PROFILE", content = "旧版低质量画像"),
            )
            insert(
                "MemoryEntity",
                SQLiteDatabase.CONFLICT_NONE,
                memory(id = 2, kind = "CONTEXT", content = "需要保留的记住事项"),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 36, true)
        val profiles = db.query("SELECT id FROM MemoryEntity WHERE kind = 'PROFILE'")
        assertFalse(profiles.moveToFirst())
        profiles.close()

        val context = db.query("SELECT * FROM MemoryEntity WHERE id = 2")
        assertTrue(context.moveToFirst())
        assertEquals("需要保留的记住事项", context.getString(context.getColumnIndexOrThrow("content")))
        assertEquals("[]", context.getString(context.getColumnIndexOrThrow("profile_evidence_json")))
        assertEquals("[]", context.getString(context.getColumnIndexOrThrow("supporting_observation_ids")))
        assertEquals("", context.getString(context.getColumnIndexOrThrow("canonical_key")))
        assertEquals(0L, context.getLong(context.getColumnIndexOrThrow("first_evidence_at")))
        context.close()
        db.close()
    }

    private fun memory(id: Int, kind: String, content: String) = ContentValues().apply {
        put("id", id)
        put("assistant_id", "__global__")
        put("content", content)
        put("kind", kind)
        put("state", "ACTIVE")
        put("created_at", 1L)
        put("updated_at", 1L)
        put("dimension_id", "preferences_values")
        put("confidence", 1f)
        put("source", "AUTO")
        put("evidence_conversation_ids", "[]")
        put("locked", false)
        put("last_evidence_at", 0L)
    }
}
