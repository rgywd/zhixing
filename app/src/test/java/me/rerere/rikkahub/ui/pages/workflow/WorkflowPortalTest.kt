package me.rerere.rikkahub.ui.pages.workflow

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowPortalTest {
    @Test
    fun `workflow entry opens the Happy web app over HTTPS`() {
        val uri = URI(WorkflowPortal.URL)

        assertEquals("https", uri.scheme)
        assertEquals("app.happy.engineering", uri.host)
    }
}
