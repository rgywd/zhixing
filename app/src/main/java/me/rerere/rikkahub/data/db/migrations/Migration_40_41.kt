package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_40_41 : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `monthly_ledger_summaries` (
                `id` TEXT NOT NULL,
                `month` TEXT NOT NULL,
                `currency` TEXT NOT NULL,
                `minor_unit` INTEGER NOT NULL,
                `expense_minor` INTEGER NOT NULL,
                `income_minor` INTEGER NOT NULL,
                `aggregation_mode` TEXT NOT NULL,
                `coverage` TEXT NOT NULL,
                `confidence_percent` INTEGER NOT NULL,
                `warnings_json` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `monthly_ledger_channels` (
                `summary_id` TEXT NOT NULL,
                `channel_key` TEXT NOT NULL,
                `channel_name` TEXT NOT NULL,
                `expense_minor` INTEGER NOT NULL,
                `income_minor` INTEGER NOT NULL,
                `coverage` TEXT NOT NULL,
                `confidence_percent` INTEGER NOT NULL,
                `warnings_json` TEXT NOT NULL,
                PRIMARY KEY(`summary_id`, `channel_key`),
                FOREIGN KEY(`summary_id`) REFERENCES `monthly_ledger_summaries`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_monthly_ledger_summaries_month_currency` " +
                "ON `monthly_ledger_summaries` (`month`, `currency`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_monthly_ledger_channels_summary_id` " +
                "ON `monthly_ledger_channels` (`summary_id`)"
        )
    }
}
