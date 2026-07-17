package me.rerere.rikkahub.ui.pages.workflow.happy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HappyProtocolTest {
    @Test
    fun `relay url is normalized to https origin`() {
        assertEquals(
            "https://happy.example.com",
            HappyProtocol.normalizeServerUrl("  https://happy.example.com/  "),
        )
        assertEquals(
            "https://happy.example.com:8443",
            HappyProtocol.normalizeServerUrl("https://happy.example.com:8443"),
        )
    }

    @Test
    fun `relay rejects cleartext credentials query and subpaths`() {
        listOf(
            "http://happy.example.com",
            "https://user:pass@happy.example.com",
            "https://happy.example.com/base",
            "https://happy.example.com?token=secret",
            "not-a-url",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                HappyProtocol.normalizeServerUrl(value)
            }
        }
    }
}
