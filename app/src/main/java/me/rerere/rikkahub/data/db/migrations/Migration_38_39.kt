package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_38_39 : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agenda_plans` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `note` TEXT NOT NULL,
                `location` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `event_at` INTEGER,
                `source` TEXT NOT NULL,
                `source_reference` TEXT,
                `conversation_id` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `completed_at` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agenda_plan_stages` (
                `id` TEXT NOT NULL,
                `plan_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `note` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `scheduled_at` INTEGER,
                `due_at` INTEGER,
                `trigger_at` INTEGER,
                `reminder_at` INTEGER,
                `scheduled_offset_minutes` INTEGER,
                `due_offset_minutes` INTEGER,
                `trigger_offset_minutes` INTEGER,
                `reminder_offset_minutes` INTEGER,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `completed_at` INTEGER,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`plan_id`) REFERENCES `agenda_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agenda_plans_status_event_at` ON `agenda_plans` (`status`, `event_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agenda_plans_updated_at` ON `agenda_plans` (`updated_at`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agenda_plan_stages_plan_id_position` " +
                "ON `agenda_plan_stages` (`plan_id`, `position`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agenda_plan_stages_status_trigger_at_due_at` " +
                "ON `agenda_plan_stages` (`status`, `trigger_at`, `due_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agenda_plan_stages_reminder_at` " +
                "ON `agenda_plan_stages` (`reminder_at`)"
        )
    }
}
