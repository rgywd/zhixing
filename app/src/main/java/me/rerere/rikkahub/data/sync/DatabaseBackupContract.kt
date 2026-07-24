package me.rerere.rikkahub.data.sync

import android.content.Context
import me.rerere.rikkahub.data.db.APP_DATABASE_NAME
import me.rerere.rikkahub.data.db.APP_DATABASE_VERSION
import me.rerere.rikkahub.data.db.createAppDatabase
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class DatabaseBackupFile(
    val archiveEntryName: String,
    val databaseFileName: String,
)

internal enum class DatabaseBackupSource {
    CURRENT,
    LEGACY_RIKKA_HUB,
}

internal data class DatabaseRestoreFiles(
    val source: DatabaseBackupSource,
    val files: List<DatabaseBackupFile>,
)

internal class DatabaseRestoreRequiresRestartException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

internal object DatabaseBackupContract {
    const val CURRENT_DATABASE_NAME = APP_DATABASE_NAME

    private const val LEGACY_DATABASE_NAME = "rikka_hub"

    fun backupFiles(): List<DatabaseBackupFile> = filesFor(
        archiveDatabaseName = CURRENT_DATABASE_NAME,
    )

    fun selectRestoreFiles(archiveEntryNames: Set<String>): DatabaseRestoreFiles? {
        val source = when {
            archiveEntryNames.contains(primaryArchiveEntry(CURRENT_DATABASE_NAME)) ->
                DatabaseBackupSource.CURRENT

            archiveEntryNames.contains(primaryArchiveEntry(LEGACY_DATABASE_NAME)) ->
                DatabaseBackupSource.LEGACY_RIKKA_HUB

            else -> return null
        }
        val archiveDatabaseName = when (source) {
            DatabaseBackupSource.CURRENT -> CURRENT_DATABASE_NAME
            DatabaseBackupSource.LEGACY_RIKKA_HUB -> LEGACY_DATABASE_NAME
        }

        return DatabaseRestoreFiles(
            source = source,
            files = filesFor(archiveDatabaseName)
                .filter { archiveEntryNames.contains(it.archiveEntryName) },
        )
    }

    fun validateStagedDatabase(
        context: Context,
        stagingDirectory: File,
    ) {
        val databaseFile = File(stagingDirectory, CURRENT_DATABASE_NAME)
        require(databaseFile.isFile && databaseFile.length() > 0L) {
            "Staged database is missing or empty"
        }

        val stagedDatabase = createAppDatabase(
            context = context,
            databaseName = databaseFile.absolutePath,
        )
        try {
            val sqliteDatabase = stagedDatabase.openHelper.writableDatabase
            require(sqliteDatabase.version == APP_DATABASE_VERSION) {
                "Staged database migration did not reach the current schema"
            }
            sqliteDatabase.query(
                """
                SELECT identity_hash
                FROM room_master_table
                WHERE id = 42
                """.trimIndent()
            ).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).isNotBlank()) {
                    "Staged database is missing its Room identity"
                }
            }
            sqliteDatabase.query(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'table' AND lower(name) = lower('ConversationEntity')
                """.trimIndent()
            ).use { cursor ->
                require(cursor.moveToFirst()) {
                    "Staged database is not a compatible app database"
                }
            }
            sqliteDatabase.query("PRAGMA quick_check(1)").use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "Staged database failed SQLite quick_check"
                }
            }
        } finally {
            stagedDatabase.close()
        }
    }

    fun resolveRestoreTarget(rootDirectory: File, relativePath: String): File {
        val normalizedRelativePath = relativePath.replace('\\', '/')
        require(
            normalizedRelativePath.isNotBlank() &&
                '\u0000' !in normalizedRelativePath &&
                !normalizedRelativePath.matches(Regex("^[A-Za-z]:/.*")) &&
                normalizedRelativePath.split('/').none { segment ->
                    segment.isBlank() || segment == "." || segment == ".."
                }
        ) {
            "Invalid backup entry path"
        }

        val canonicalRoot = rootDirectory.canonicalFile
        val canonicalTarget = File(canonicalRoot, normalizedRelativePath).canonicalFile
        require(
            canonicalTarget != canonicalRoot &&
                canonicalTarget.toPath().startsWith(canonicalRoot.toPath())
        ) {
            "Backup entry escapes its restore directory"
        }
        return canonicalTarget
    }

    fun installStagedDatabase(
        stagingDirectory: File,
        targetDirectory: File,
    ) {
        val stagedFiles = backupFiles()
            .associate { backupFile ->
                backupFile.databaseFileName to File(stagingDirectory, backupFile.databaseFileName)
            }
            .filterValues(File::isFile)
        require(stagedFiles.getValue(CURRENT_DATABASE_NAME).isFile) {
            "Staged database is missing"
        }

        targetDirectory.mkdirs()
        val rollbackDirectory = File(
            targetDirectory,
            ".$CURRENT_DATABASE_NAME-restore-rollback-${System.nanoTime()}",
        )
        require(rollbackDirectory.mkdirs()) {
            "Could not create database restore rollback directory"
        }
        val targetNames = backupFiles().map(DatabaseBackupFile::databaseFileName)
        val movedToRollback = mutableListOf<Pair<File, File>>()
        val installedTargets = mutableListOf<File>()
        var cleanupRollback = false

        try {
            targetNames.forEach { fileName ->
                val target = File(targetDirectory, fileName)
                if (target.exists()) {
                    val rollback = File(rollbackDirectory, fileName)
                    moveReplacing(target, rollback)
                    movedToRollback += target to rollback
                }
            }
            stagedFiles.forEach { (fileName, stagedFile) ->
                require(stagedFile.isFile) { "Staged database file $fileName is missing" }
                val target = File(targetDirectory, fileName)
                moveReplacing(stagedFile, target)
                installedTargets += target
            }
            cleanupRollback = true
        } catch (error: Throwable) {
            installedTargets.forEach { installed ->
                runCatching { Files.deleteIfExists(installed.toPath()) }
            }
            var rollbackFailure: Throwable? = null
            movedToRollback.asReversed().forEach { (target, rollback) ->
                runCatching { moveReplacing(rollback, target) }
                    .onFailure { failure ->
                        if (rollbackFailure == null) {
                            rollbackFailure = failure
                        } else {
                            rollbackFailure.addSuppressed(failure)
                        }
                    }
            }
            if (rollbackFailure == null) {
                cleanupRollback = true
            } else {
                error.addSuppressed(
                    IllegalStateException(
                        "Database restore rollback failed; recovery files remain at " +
                            rollbackDirectory.absolutePath,
                        rollbackFailure,
                    )
                )
            }
            throw error
        } finally {
            if (cleanupRollback) {
                rollbackDirectory.deleteRecursively()
            }
        }
    }

    private fun filesFor(archiveDatabaseName: String): List<DatabaseBackupFile> = listOf(
        DatabaseBackupFile(
            archiveEntryName = primaryArchiveEntry(archiveDatabaseName),
            databaseFileName = CURRENT_DATABASE_NAME,
        ),
        DatabaseBackupFile(
            archiveEntryName = "$archiveDatabaseName-wal",
            databaseFileName = "$CURRENT_DATABASE_NAME-wal",
        ),
        DatabaseBackupFile(
            archiveEntryName = "$archiveDatabaseName-shm",
            databaseFileName = "$CURRENT_DATABASE_NAME-shm",
        ),
    )

    private fun primaryArchiveEntry(databaseName: String): String = "$databaseName.db"

    private fun moveReplacing(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
