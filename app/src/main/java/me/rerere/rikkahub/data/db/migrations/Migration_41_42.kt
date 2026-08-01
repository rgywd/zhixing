package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_41_42 : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `agenda_tasks` ADD COLUMN `recurrence_frequency` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `agenda_tasks` ADD COLUMN `recurrence_interval` INTEGER NOT NULL DEFAULT 1")
    }
}
