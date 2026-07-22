package me.rerere.asr.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VolcengineASRControllerTest {
    @Test
    fun websocketRequestUsesAgentPlanHeaders() {
        val request = buildVolcengineAsrWebSocketRequest(
            websocketUrl = "wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async",
            apiKey = "test-key",
            resourceId = "volc.seedasr.sauc.duration",
        )

        assertEquals("test-key", request.header("X-Api-Key"))
        assertEquals("volc.seedasr.sauc.duration", request.header("X-Api-Resource-Id"))
        assertFalse(request.header("X-Api-Request-Id").isNullOrBlank())
        assertFalse(request.header("X-Api-Connect-Id").isNullOrBlank())
        assertEquals("-1", request.header("X-Api-Sequence"))
    }
}
