package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `ConversationEntity` " +
                "ADD COLUMN `user_prompt_snapshot_json` TEXT NOT NULL DEFAULT ''"
        )
    }
}
