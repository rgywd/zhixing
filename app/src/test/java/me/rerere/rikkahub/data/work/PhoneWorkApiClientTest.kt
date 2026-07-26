package me.rerere.rikkahub.data.work

import me.rerere.rikkahub.data.life.InformationMonitorFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneWorkApiClientTest {
    @Test
    fun `information monitor freshness defaults to fresh and preserves stale`() {
        assertEquals(InformationMonitorFreshness.FRESH, parseInformationMonitorFreshness(null))
        assertEquals(InformationMonitorFreshness.FRESH, parseInformationMonitorFreshness(""))
        assertEquals(InformationMonitorFreshness.FRESH, parseInformationMonitorFreshness("fresh"))
        assertEquals(InformationMonitorFreshness.STALE, parseInformationMonitorFreshness("stale"))
        assertEquals(InformationMonitorFreshness.STALE, parseInformationMonitorFreshness(" STALE "))
    }

    @Test
    fun `information monitor freshness rejects an unknown transport value`() {
        assertThrows(PhoneWorkApiException::class.java) {
            parseInformationMonitorFreshness("unknown")
        }
    }
}
