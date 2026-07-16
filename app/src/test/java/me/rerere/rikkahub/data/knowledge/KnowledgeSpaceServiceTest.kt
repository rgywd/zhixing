package me.rerere.rikkahub.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class KnowledgeSpaceServiceTest {
    @Test
    fun textDocumentsAreNormalizedLocally() {
        val file = Files.createTempFile("knowledge", ".md").toFile().apply {
            writeText("中文项目资料")
        }

        assertEquals(
            "中文项目资料",
            normalizeKnowledgeDocument(file, extension = "md", mimeType = "text/markdown"),
        )
    }

    @Test
    fun unsupportedBinaryIsPreservedButNotIndexed() {
        val file = Files.createTempFile("knowledge", ".bin").toFile().apply {
            writeBytes(byteArrayOf(0, 1, 2, 3))
        }

        assertNull(normalizeKnowledgeDocument(file, extension = "bin", mimeType = "application/octet-stream"))
    }
}
