package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_36_37 : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE phone_work_sessions ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE phone_work_sessions SET title = repo_name WHERE title = ''")
    }
}
