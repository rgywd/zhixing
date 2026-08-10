package me.rerere.rikkahub.data.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneWorkAttachmentsTest {
    @Test
    fun `work accepts common documents source files and requested archives`() {
        assertTrue(isAllowedWorkAttachmentType("notes.md", "text/markdown"))
        assertTrue(isAllowedWorkAttachmentType("report.pdf", "application/pdf"))
        assertTrue(isAllowedWorkAttachmentType("source.zip", "application/zip"))
        assertTrue(isAllowedWorkAttachmentType("source.7z", "application/octet-stream"))
        assertTrue(isAllowedWorkAttachmentType("logs.tar.gz", "application/gzip"))
    }

    @Test
    fun `work rejects arbitrary executable attachments`() {
        assertFalse(isAllowedWorkAttachmentType("setup.exe", "application/octet-stream"))
        assertFalse(isAllowedWorkAttachmentType("payload.apk", "application/vnd.android.package-archive"))
    }

    @Test
    fun `catalog gates files on the selected runner capability`() {
        val catalog = PhoneWorkCatalog(
            runners = listOf(
                PhoneWorkRunner(
                    id = "new",
                    name = "new",
                    version = "2",
                    online = true,
                    capabilities = PhoneWorkRunnerCapabilities(fileAttachments = 1),
                ),
                PhoneWorkRunner(id = "old", name = "old", version = "1", online = true),
            ),
        )

        assertTrue(catalog.supportsFileAttachments("new"))
        assertFalse(catalog.supportsFileAttachments("old"))
        assertFalse(catalog.supportsFileAttachments("missing"))
    }
}
