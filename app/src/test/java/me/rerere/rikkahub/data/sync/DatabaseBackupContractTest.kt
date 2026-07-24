package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DatabaseBackupContractTest {
    @Test
    fun `new backups contain the current database and its wal sidecars`() {
        assertEquals(
            listOf(
                DatabaseBackupFile("zhixing.db", "zhixing"),
                DatabaseBackupFile("zhixing-wal", "zhixing-wal"),
                DatabaseBackupFile("zhixing-shm", "zhixing-shm"),
            ),
            DatabaseBackupContract.backupFiles(),
        )
    }

    @Test
    fun `current backup entries restore into the current database`() {
        val restoreFiles = DatabaseBackupContract.selectRestoreFiles(
            setOf("settings.json", "zhixing.db", "zhixing-wal", "zhixing-shm"),
        )

        assertEquals(DatabaseBackupSource.CURRENT, restoreFiles?.source)
        assertEquals(
            listOf(
                DatabaseBackupFile("zhixing.db", "zhixing"),
                DatabaseBackupFile("zhixing-wal", "zhixing-wal"),
                DatabaseBackupFile("zhixing-shm", "zhixing-shm"),
            ),
            restoreFiles?.files,
        )
    }

    @Test
    fun `legacy backup entries restore into the current database without a missing error`() {
        val restoreFiles = DatabaseBackupContract.selectRestoreFiles(
            setOf("settings.json", "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm"),
        )

        assertEquals(DatabaseBackupSource.LEGACY_RIKKA_HUB, restoreFiles?.source)
        assertEquals(
            listOf(
                DatabaseBackupFile("rikka_hub.db", "zhixing"),
                DatabaseBackupFile("rikka_hub-wal", "zhixing-wal"),
                DatabaseBackupFile("rikka_hub-shm", "zhixing-shm"),
            ),
            restoreFiles?.files,
        )
    }

    @Test
    fun `wal sidecars remain optional for legacy backups`() {
        val restoreFiles = DatabaseBackupContract.selectRestoreFiles(
            setOf("settings.json", "rikka_hub.db"),
        )

        assertEquals(DatabaseBackupSource.LEGACY_RIKKA_HUB, restoreFiles?.source)
        assertEquals(
            listOf(DatabaseBackupFile("rikka_hub.db", "zhixing")),
            restoreFiles?.files,
        )
    }

    @Test
    fun `current database wins when an archive contains both layouts`() {
        val restoreFiles = DatabaseBackupContract.selectRestoreFiles(
            setOf(
                "zhixing.db",
                "zhixing-wal",
                "rikka_hub.db",
                "rikka_hub-wal",
            ),
        )

        assertEquals(DatabaseBackupSource.CURRENT, restoreFiles?.source)
        assertEquals(
            listOf(
                DatabaseBackupFile("zhixing.db", "zhixing"),
                DatabaseBackupFile("zhixing-wal", "zhixing-wal"),
            ),
            restoreFiles?.files,
        )
    }

    @Test
    fun `archive without a supported primary database has no restore plan`() {
        assertNull(
            DatabaseBackupContract.selectRestoreFiles(
                setOf("settings.json", "zhixing-wal", "rikka_hub-shm"),
            )
        )
    }

    @Test
    fun `primary-only restore replaces database and removes stale sidecars`() {
        val root = Files.createTempDirectory("database-restore-test").toFile()
        try {
            val staging = root.resolve("staging").apply { mkdirs() }
            val target = root.resolve("target").apply { mkdirs() }
            staging.resolve("zhixing").writeText("restored")
            target.resolve("zhixing").writeText("current")
            target.resolve("zhixing-wal").writeText("stale-wal")
            target.resolve("zhixing-shm").writeText("stale-shm")
            DatabaseBackupContract.installStagedDatabase(
                stagingDirectory = staging,
                targetDirectory = target,
            )

            assertEquals("restored", target.resolve("zhixing").readText())
            assertFalse(target.resolve("zhixing-wal").exists())
            assertFalse(target.resolve("zhixing-shm").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restore target accepts a nested path inside its root`() {
        val root = Files.createTempDirectory("backup-path-test").toFile()
        try {
            assertEquals(
                File(root, "images/avatar.png").canonicalFile,
                DatabaseBackupContract.resolveRestoreTarget(root, "images/avatar.png"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restore target rejects traversal and absolute-like paths`() {
        val root = Files.createTempDirectory("backup-path-test").toFile()
        try {
            listOf(
                "../databases/zhixing",
                "images/../../databases/zhixing",
                "images\\..\\..\\databases\\zhixing",
                "/databases/zhixing",
                "C:\\databases\\zhixing",
                "images//avatar.png",
                "images/\u0000avatar.png",
            ).forEach { unsafePath ->
                assertThrows(IllegalArgumentException::class.java) {
                    DatabaseBackupContract.resolveRestoreTarget(root, unsafePath)
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
