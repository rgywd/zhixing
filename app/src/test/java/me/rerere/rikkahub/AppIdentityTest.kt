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
        if (AppIdentity.distributionChannel == "production") {
            assertEquals(
                "https://github.com/rgywd/zhixing/releases/latest/download/latest.json",
                AppIdentity.updateFeedUrl,
            )
        } else {
            assertEquals("staging", AppIdentity.distributionChannel)
            assertTrue(AppIdentity.updateFeedUrl.isBlank())
        }
        assertEquals("${AppIdentity.distributionRepositoryUrl}/releases", AppIdentity.sourceUrl)
        assertEquals("https://github.com/rgywd/zhixing/issues", AppIdentity.issueTrackerUrl)
        assertTrue(AppIdentity.licenseUrl.startsWith(AppIdentity.distributionRepositoryUrl))
        assertTrue(AppIdentity.thirdPartyNoticesUrl.startsWith(AppIdentity.distributionRepositoryUrl))
    }
}
