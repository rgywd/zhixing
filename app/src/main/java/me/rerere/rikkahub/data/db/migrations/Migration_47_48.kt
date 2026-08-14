package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE phone_work_sessions ADD COLUMN active_turn_id TEXT")
    }
}
