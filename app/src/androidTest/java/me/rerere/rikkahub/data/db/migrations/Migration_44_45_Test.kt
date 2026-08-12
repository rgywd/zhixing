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
class Migration_44_45_Test {
    private val testDb = "user-prompt-snapshot-migration-44-45"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun addsEmptySnapshotWithoutChangingExistingConversation() {
        helper.createDatabase(testDb, 44).apply {
            insert(
                "ConversationEntity",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "conversation-1")
                    put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
                    put("title", "旧对话")
                    put("nodes", "[]")
                    put("create_at", 1L)
                    put("update_at", 2L)
                    put("suggestions", "[]")
                    put("is_pinned", false)
                    put("custom_system_prompt", "旧用户提示词")
                    put("mode_injection_ids", "[]")
                    put("lorebook_ids", "[]")
                    put("workspace_cwd", "")
                    put("folder_id", "")
                },
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 45, true, Migration_44_45)
        db.query(
            "SELECT title, custom_system_prompt, user_prompt_snapshot_json " +
                "FROM ConversationEntity WHERE id = 'conversation-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧对话", cursor.getString(0))
            assertEquals("旧用户提示词", cursor.getString(1))
            assertEquals("", cursor.getString(2))
        }
        db.close()
    }
}
