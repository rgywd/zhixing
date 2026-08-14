package me.rerere.rikkahub.data.work

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.life.InformationMonitorFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun `follow-up message serializes an optional reasoning effort`() {
        val encoded = Json.encodeToString(
            SendMessageRequest(
                text = "请深度复查",
                attachmentIds = emptyList(),
                reasoningEffort = "xhigh",
                clientMessageId = "message-1",
            ),
        )

        assertTrue(encoded.contains("\"reasoningEffort\":\"xhigh\""))
    }

    @Test
    fun `follow-up message serializes an optional Fast mode change`() {
        val encoded = Json.encodeToString(
            SendMessageRequest(
                text = "快速继续",
                attachmentIds = emptyList(),
                fastMode = true,
                clientMessageId = "message-fast",
            ),
        )

        assertTrue(encoded.contains("\"fastMode\":true"))
    }

    @Test
    fun `legacy follow-up message omits a missing reasoning effort`() {
        val encoded = Json.encodeToString(
            SendMessageRequest(
                text = "继续",
                attachmentIds = emptyList(),
                clientMessageId = "message-2",
            ),
        )

        assertFalse(encoded.contains("reasoningEffort"))
        assertFalse(encoded.contains("fastMode"))
    }

    @Test
    fun `queued message carries a client id and optional next-turn settings`() {
        val encoded = Json.encodeToString(
            QueueMessageRequest(
                text = "下一轮先检查测试",
                attachmentIds = listOf("attachment-1"),
                reasoningEffort = "high",
                fastMode = true,
                clientMessageId = "queue-1",
            ),
        )

        assertTrue(encoded.contains("\"clientMessageId\":\"queue-1\""))
        assertTrue(encoded.contains("\"reasoningEffort\":\"high\""))
        assertTrue(encoded.contains("\"fastMode\":true"))
    }

    @Test
    fun `steer request pins input to the expected active turn`() {
        val encoded = Json.encodeToString(
            SteerRequest(
                text = "改为先处理登录问题",
                expectedTurnId = "turn-7",
                clientMessageId = "steer-1",
            ),
        )

        assertTrue(encoded.contains("\"expectedTurnId\":\"turn-7\""))
        assertTrue(encoded.contains("\"clientMessageId\":\"steer-1\""))
    }
}
