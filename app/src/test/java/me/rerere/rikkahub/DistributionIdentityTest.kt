package me.rerere.rikkahub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributionIdentityTest {
    @Test
    fun `distribution identity keeps staging isolated from production`() {
        when (AppIdentity.distributionChannel) {
            "staging" -> {
                assertEquals("dev.sundby.zhixing.staging", BuildConfig.APPLICATION_ID)
                assertTrue(AppIdentity.stagingTestDriverEnabled)
                assertTrue(AppIdentity.updateFeedUrl.isBlank())
            }

            "production" -> {
                assertFalse(AppIdentity.stagingTestDriverEnabled)
                assertTrue(AppIdentity.updateFeedUrl.contains("gitee.com/api/v5/repos/rongguiyewd/zhixing/releases/latest"))
            }

            else -> error("Unknown distribution channel: ${AppIdentity.distributionChannel}")
        }
    }
}
