package me.rerere.rikkahub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdentityTest {
    @Test
    fun `zhixing identity does not depend on upstream hosted services`() {
        assertEquals("Zhixing", AppIdentity.productName)
        assertFalse(AppIdentity.thirdPartyTelemetryEnabled)
        assertNull(AppIdentity.updateFeedUrl)
        assertTrue(AppIdentity.sourceUrl.contains("zhixing-assistant"))
        assertTrue(AppIdentity.upstreamSourceUrl.contains("rikkahub"))
    }
}
