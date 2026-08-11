package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `MemoryDocumentEntity` (
                `scope_id` TEXT NOT NULL,
                `path` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `aliases_json` TEXT NOT NULL DEFAULT '[]',
                `content` TEXT NOT NULL DEFAULT '',
                `sources_json` TEXT NOT NULL DEFAULT '[]',
                `version` INTEGER NOT NULL DEFAULT 1,
                `state` TEXT NOT NULL DEFAULT 'ACTIVE',
                `created_at` INTEGER NOT NULL DEFAULT 0,
                `updated_at` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`scope_id`, `path`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_MemoryDocumentEntity_scope_id_state_updated_at`
            ON `MemoryDocumentEntity` (`scope_id`, `state`, `updated_at`)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO MemoryDocumentEntity(
                scope_id, path, name, description, aliases_json, content,
                sources_json, version, state, created_at, updated_at
            ) VALUES(
                '__global__', '/profile.md', 'Profile',
                'Stable user identity and background stated by the user.', '[]', '',
                '[]', 1, 'ACTIVE', 0, 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO MemoryDocumentEntity(
                scope_id, path, name, description, aliases_json, content,
                sources_json, version, state, created_at, updated_at
            ) VALUES(
                '__global__', '/preferences.md', 'Preferences',
                'Stable response and collaboration preferences stated by the user.', '[]', '',
                '[]', 1, 'ACTIVE', 0, 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO MemoryDocumentEntity(
                scope_id, path, name, description, aliases_json, content,
                sources_json, version, state, created_at, updated_at
            )
            SELECT assistant_id,
                   CASE WHEN assistant_id = '__global__'
                        THEN '/archive/legacy-memory.md'
                        ELSE '/archive/legacy-memory-' || assistant_id || '.md' END,
                   'Legacy memory archive',
                   'Records preserved from the pre-V3 memory system; never loaded automatically.',
                   '[]',
                   GROUP_CONCAT('- [legacy] #' || id || ' ' || content, CHAR(10)),
                   '[{"type":"MIGRATION"}]',
                   1,
                   'ACTIVE',
                   MIN(created_at),
                   MAX(updated_at)
            FROM MemoryEntity
            WHERE state != 'DELETED'
            GROUP BY assistant_id
            """.trimIndent()
        )
    }
}
