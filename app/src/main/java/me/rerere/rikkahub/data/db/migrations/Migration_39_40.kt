package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_39_40 : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `phone_work_sessions` ADD COLUMN `runtime` TEXT NOT NULL DEFAULT 'codex'")
        db.execSQL("ALTER TABLE `phone_work_sessions` ADD COLUMN `runtime_session_id` TEXT")
        db.execSQL(
            """
            UPDATE `phone_work_sessions`
            SET `runtime_session_id` = `codex_session_id`
            WHERE `runtime_session_id` IS NULL
            """.trimIndent()
        )
    }
}
