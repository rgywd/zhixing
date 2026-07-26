package me.rerere.rikkahub.data.files

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class FilesManagerDeletionTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("files-manager-deletion-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun metadataFailureIsCountedAndDoesNotStopLaterTargets() = runBlocking {
        val first = File(root, "first.png").apply { writeText("first") }
        val second = File(root, "second.png")
        val metadataAttempts = mutableListOf<String>()

        val result = deleteManagedUploadTargets(
            targets = listOf(
                ManagedUploadFile(first, "upload/first.png"),
                null,
                ManagedUploadFile(second, "upload/second.png"),
            ),
            deleteMetadata = { relativePath ->
                metadataAttempts += relativePath
                if (relativePath == "upload/first.png") {
                    error("database unavailable")
                }
            },
        )

        assertEquals(
            ManagedFileDeleteResult(
                requested = 3,
                removed = 1,
                rejected = 1,
                failed = 1,
            ),
            result,
        )
        assertFalse(first.exists())
        assertEquals(
            listOf("upload/first.png", "upload/second.png"),
            metadataAttempts,
        )
    }

    @Test
    fun cancellationIsRethrown() {
        val target = ManagedUploadFile(
            file = File(root, "missing.png"),
            relativePath = "upload/missing.png",
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                deleteManagedUploadTargets(listOf(target)) {
                    throw CancellationException("cancelled")
                }
            }
        }
    }
}
