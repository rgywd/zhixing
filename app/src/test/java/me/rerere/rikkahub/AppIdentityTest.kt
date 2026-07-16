package me.rerere.rikkahub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdentityTest {
    @Test
    fun `zhixing identity does not depend on upstream hosted services`() {
        assertEquals("Zhixing", AppIdentity.productName)
        assertFalse(AppIdentity.thirdPartyTelemetryEnabled)
        assertTrue(AppIdentity.updateFeedUrl.contains("github.com/rgywd/zhixing/releases"))
        assertTrue(AppIdentity.sourceUrl.endsWith("rgywd/zhixing"))
        assertEquals("${AppIdentity.sourceUrl}/issues", AppIdentity.issueTrackerUrl)
    }
}
