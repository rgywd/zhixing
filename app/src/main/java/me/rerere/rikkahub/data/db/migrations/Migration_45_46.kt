package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM assistant_task_links")
        db.execSQL("DELETE FROM assistant_task_events")
        db.execSQL("DELETE FROM assistant_tasks")
    }
}
