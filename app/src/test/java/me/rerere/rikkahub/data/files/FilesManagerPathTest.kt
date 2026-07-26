package me.rerere.rikkahub.data.files

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FilesManagerPathTest {
    private lateinit var root: File
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("files-manager-path-test").toFile()
        filesDir = File(root, "files").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun acceptsFileContainedByUploadDirectory() {
        val file = File(filesDir, "upload/receipt.png")

        val result = resolveManagedUploadFile(filesDir, "file", file)

        assertEquals(file.canonicalFile, result?.file)
        assertEquals("upload/receipt.png", result?.relativePath)
    }

    @Test
    fun rejectsPathEscapeAndExternalFiles() {
        val escaped = File(filesDir, "upload/../outside.txt")
        val external = File(root, "external.txt")

        assertNull(resolveManagedUploadFile(filesDir, "file", escaped))
        assertNull(resolveManagedUploadFile(filesDir, "file", external))
    }

    @Test
    fun rejectsNonFileSchemesAndUploadDirectoryItself() {
        val file = File(filesDir, "upload/receipt.png")
        val uploadDir = File(filesDir, "upload")

        assertNull(resolveManagedUploadFile(filesDir, "http", file))
        assertNull(resolveManagedUploadFile(filesDir, null, file))
        assertNull(resolveManagedUploadFile(filesDir, "file", uploadDir))
    }
}
