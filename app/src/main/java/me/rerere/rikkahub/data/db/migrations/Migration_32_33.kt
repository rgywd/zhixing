package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Removes the retired Happy/App Server/Relay Work data model. */
object Migration_32_33 : Migration(32, 33) {
    private val retiredTables = listOf(
        "codex_approvals",
        "codex_items",
        "codex_turns",
        "codex_attachments",
        "codex_drafts",
        "codex_thread_detail_revisions",
        "codex_thread_preferences",
        "codex_runtime_bindings",
        "codex_runtime_catalogs",
        "codex_runtime_settings",
        "codex_catalog_chunks",
        "codex_catalog_sync",
        "codex_tombstones",
        "codex_threads",
        "codex_project_preferences",
        "codex_projects",
        "codex_machines",
        "work_messages",
        "work_sessions",
        "work_repo_presets",
        "work_machines",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        retiredTables.forEach { table -> db.execSQL("DROP TABLE IF EXISTS `$table`") }
    }
}
