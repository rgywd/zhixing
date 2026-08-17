package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `health_metric_records` (
                `id` TEXT NOT NULL,
                `metric_type` TEXT NOT NULL,
                `value_decimal` TEXT NOT NULL,
                `unit` TEXT NOT NULL,
                `observed_at_epoch_ms` INTEGER,
                `recorded_at_epoch_ms` INTEGER NOT NULL,
                `effective_at_epoch_ms` INTEGER NOT NULL,
                `source_type` TEXT NOT NULL,
                `source_conversation_id` TEXT,
                `source_message_id` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_health_metric_records_metric_type_effective_at_epoch_ms` " +
                "ON `health_metric_records` (`metric_type`, `effective_at_epoch_ms`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_health_metric_records_recorded_at_epoch_ms` " +
                "ON `health_metric_records` (`recorded_at_epoch_ms`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_health_metric_records_source_message_id` " +
                "ON `health_metric_records` (`source_message_id`)",
        )
    }
}
