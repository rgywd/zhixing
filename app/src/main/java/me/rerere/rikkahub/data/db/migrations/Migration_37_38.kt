package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_37_38 : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agenda_tasks` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `note` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `due_at` INTEGER,
                `reminder_at` INTEGER,
                `source` TEXT NOT NULL,
                `conversation_id` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `completed_at` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agenda_tasks_status_due_at` ON `agenda_tasks` (`status`, `due_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agenda_tasks_reminder_at` ON `agenda_tasks` (`reminder_at`)")
    }
}
