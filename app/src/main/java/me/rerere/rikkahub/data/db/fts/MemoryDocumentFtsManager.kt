package me.rerere.rikkahub.data.db.fts

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase

data class MemoryDocumentSearchHit(
    val scopeId: String,
    val path: String,
    val name: String,
    val description: String,
    val aliasesJson: String,
    val version: Long,
)

data class MemoryDocumentSearchVisibility(
    val contextScopeId: String,
    val globalScopeId: String,
    val globalPinnedPaths: List<String>,
)

fun interface MemoryDocumentSearchIndex {
    suspend fun search(
        query: String,
        prefix: String,
        limit: Int,
        visibility: MemoryDocumentSearchVisibility,
    ): List<MemoryDocumentSearchHit>
}

/**
 * A disposable FTS projection over active memory documents. Room remains the source of truth.
 * Database triggers keep mutations transactionally aligned with this projection, while every open
 * rebuilds it to recover from upgrades, restores, dictionary changes, or prior index damage.
 */
class MemoryDocumentFtsManager(
    private val database: AppDatabase,
) : MemoryDocumentSearchIndex {
    private val db get() = database.openHelper.writableDatabase

    override suspend fun search(
        query: String,
        prefix: String,
        limit: Int,
        visibility: MemoryDocumentSearchVisibility,
    ): List<MemoryDocumentSearchHit> = withContext(Dispatchers.IO) {
        val arguments = mutableListOf<Any?>(query)
        val visibilityClause = if (visibility.contextScopeId == visibility.globalScopeId) {
            arguments += visibility.globalScopeId
            "document.scope_id = ?"
        } else {
            arguments += visibility.contextScopeId
            arguments += visibility.globalScopeId
            visibility.globalPinnedPaths.forEach { arguments += it }
            val pinnedPlaceholders = visibility.globalPinnedPaths.joinToString(",") { "?" }
            "(document.scope_id = ? OR (document.scope_id = ? AND document.path IN ($pinnedPlaceholders)))"
        }
        val prefixClause = if (prefix == "/") {
            "1 = 1"
        } else {
            arguments += "$prefix/%"
            "document.path LIKE ?"
        }
        arguments += limit

        val results = mutableListOf<MemoryDocumentSearchHit>()
        val cursor = db.query(
            """
            SELECT document.scope_id,
                   document.path,
                   document.name,
                   document.description,
                   document.aliases_json,
                   document.version
            FROM memory_document_fts
            JOIN MemoryDocumentEntity AS document ON document.rowid = memory_document_fts.rowid
            WHERE memory_document_fts MATCH jieba_query(?)
              AND document.state = 'ACTIVE'
              AND $visibilityClause
              AND $prefixClause
            ORDER BY bm25(memory_document_fts, 8.0, 8.0, 3.0, 6.0, 1.0, 0.0),
                     document.updated_at DESC,
                     document.path ASC
            LIMIT ?
            """.trimIndent(),
            arguments.toTypedArray(),
        )
        cursor.use {
            while (it.moveToNext()) {
                results += MemoryDocumentSearchHit(
                    scopeId = it.getString(0),
                    path = it.getString(1),
                    name = it.getString(2),
                    description = it.getString(3),
                    aliasesJson = it.getString(4),
                    version = it.getLong(5),
                )
            }
        }
        results
    }

    companion object {
        internal fun ensureSchemaAndRebuild(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS memory_document_fts USING fts5(
                    path,
                    name,
                    description,
                    aliases,
                    content,
                    scope_id UNINDEXED,
                    tokenize = 'simple'
                )
                """.trimIndent()
            )
            db.execSQL("DROP TRIGGER IF EXISTS memory_document_fts_after_insert")
            db.execSQL("DROP TRIGGER IF EXISTS memory_document_fts_after_update")
            db.execSQL("DROP TRIGGER IF EXISTS memory_document_fts_after_delete")
            db.execSQL(
                """
                CREATE TRIGGER memory_document_fts_after_insert
                AFTER INSERT ON MemoryDocumentEntity
                WHEN new.state = 'ACTIVE'
                BEGIN
                    INSERT INTO memory_document_fts(rowid, path, name, description, aliases, content, scope_id)
                    VALUES (
                        new.rowid, new.path, new.name, new.description, new.aliases_json, new.content, new.scope_id
                    );
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER memory_document_fts_after_update
                AFTER UPDATE ON MemoryDocumentEntity
                BEGIN
                    DELETE FROM memory_document_fts WHERE rowid = old.rowid;
                    INSERT INTO memory_document_fts(rowid, path, name, description, aliases, content, scope_id)
                    SELECT new.rowid, new.path, new.name, new.description, new.aliases_json, new.content, new.scope_id
                    WHERE new.state = 'ACTIVE';
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER memory_document_fts_after_delete
                AFTER DELETE ON MemoryDocumentEntity
                BEGIN
                    DELETE FROM memory_document_fts WHERE rowid = old.rowid;
                END
                """.trimIndent()
            )
            db.execSQL("DELETE FROM memory_document_fts")
            db.execSQL(
                """
                INSERT INTO memory_document_fts(rowid, path, name, description, aliases, content, scope_id)
                SELECT rowid, path, name, description, aliases_json, content, scope_id
                FROM MemoryDocumentEntity
                WHERE state = 'ACTIVE'
                """.trimIndent()
            )
        }
    }
}
