package me.rerere.rikkahub.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.createAppDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseBackupContractInstrumentedTest {
    @Test
    fun absoluteStagingPathOpensWithProductionRoomConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stagingDirectory = context.cacheDir
            .resolve("database-backup-contract-${System.nanoTime()}")
            .apply { mkdirs() }
        val databaseFile = stagingDirectory.resolve(DatabaseBackupContract.CURRENT_DATABASE_NAME)

        try {
            val database = createAppDatabase(
                context = context,
                databaseName = databaseFile.absolutePath,
            )
            try {
                database.openHelper.writableDatabase
            } finally {
                database.close()
            }

            DatabaseBackupContract.validateStagedDatabase(
                context = context,
                stagingDirectory = stagingDirectory,
            )

            assertTrue(databaseFile.isFile)
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }
}
