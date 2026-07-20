package me.rerere.rikkahub.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServerAttachmentManifestTest {
    @Test
    fun `manifest is model visible but can be removed from rendered text`() {
        val encoded = AppServerAttachmentManifest.append(
            "请检查文件",
            listOf(AppServerAttachedFile("spec.md", "C:/uploads/spec.md", "text/markdown")),
        )

        assertTrue(encoded.contains("C:/uploads/spec.md"))
        val parsed = AppServerAttachmentManifest.parse(encoded)
        assertEquals("请检查文件", parsed.visibleText)
        assertEquals(listOf("spec.md"), parsed.files.map { it.name })
        assertFalse(parsed.visibleText.contains("zhixing_file_attachments"))
    }
}
