package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_42_43 : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `assistant_tasks` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `attempt` INTEGER NOT NULL,
                `conversation_id` TEXT,
                `anchor_message_id` TEXT,
                `anchor_node_id` TEXT,
                `summary` TEXT,
                `result_kind` TEXT,
                `result_ref` TEXT,
                `error_code` TEXT,
                `attention_reason` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `started_at` INTEGER NOT NULL,
                `finished_at` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_assistant_tasks_conversation_id_status` ON `assistant_tasks` (`conversation_id`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_assistant_tasks_updated_at` ON `assistant_tasks` (`updated_at`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `assistant_task_events` (
                `task_id` TEXT NOT NULL,
                `seq` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `message` TEXT NOT NULL,
                `result_ref` TEXT,
                `error_code` TEXT,
                `idempotency_key` TEXT,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`, `seq`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_assistant_task_events_task_id_idempotency_key` ON `assistant_task_events` (`task_id`, `idempotency_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_assistant_task_events_created_at` ON `assistant_task_events` (`created_at`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `assistant_task_links` (
                `task_id` TEXT NOT NULL,
                `object_type` TEXT NOT NULL,
                `object_id` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`task_id`, `object_type`, `object_id`, `role`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_assistant_task_links_object_type_object_id` ON `assistant_task_links` (`object_type`, `object_id`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `assistant_runtime_contexts` (
                `id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `message_id` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `recommendation` TEXT,
                `generated_at` INTEGER NOT NULL,
                `valid_until` INTEGER NOT NULL,
                `privacy_scope_json` TEXT NOT NULL,
                `evidence_json` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_assistant_runtime_contexts_conversation_id` ON `assistant_runtime_contexts` (`conversation_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_assistant_runtime_contexts_message_id` ON `assistant_runtime_contexts` (`message_id`)")
    }
}
