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
class Migration_43_44_Test {
    private val testDb = "memory-document-migration-43-44"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun seedsPinnedDocumentsAndPreservesLegacyMemoryAsReadOnlyArchive() {
        helper.createDatabase(testDb, 43).apply {
            insert(
                "MemoryEntity",
                SQLiteDatabase.CONFLICT_NONE,
                memory(id = 1, kind = "PROFILE", content = "旧自动画像"),
            )
            insert(
                "MemoryEntity",
                SQLiteDatabase.CONFLICT_NONE,
                memory(id = 2, kind = "CONTEXT", content = "旧情境记忆"),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 44, true, Migration_43_44)
        db.query(
            "SELECT path, content FROM MemoryDocumentEntity WHERE scope_id = '__global__' ORDER BY path"
        ).use { cursor ->
            val documents = buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
            assertTrue("/profile.md" in documents)
            assertTrue("/preferences.md" in documents)
            val archive = documents.getValue("/archive/legacy-memory.md")
            assertTrue(archive.contains("- [legacy] #1 旧自动画像"))
            assertTrue(archive.contains("- [legacy] #2 旧情境记忆"))
        }

        db.query("SELECT COUNT(*) FROM MemoryEntity").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        db.close()
    }

    private fun memory(id: Int, kind: String, content: String) = ContentValues().apply {
        put("id", id)
        put("assistant_id", "__global__")
        put("content", content)
        put("kind", kind)
        put("state", "ACTIVE")
        put("created_at", id.toLong())
        put("updated_at", id.toLong())
        put("dimension_id", "preferences_values")
        put("confidence", 1f)
        put("source", "AUTO")
        put("evidence_conversation_ids", "[]")
        put("profile_evidence_json", "[]")
        put("supporting_observation_ids", "[]")
        put("canonical_key", "")
        put("first_evidence_at", 0L)
        put("locked", false)
        put("last_evidence_at", 0L)
    }
}
