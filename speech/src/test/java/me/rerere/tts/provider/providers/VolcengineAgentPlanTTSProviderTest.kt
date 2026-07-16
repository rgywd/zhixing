package me.rerere.tts.provider.providers

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolcengineAgentPlanTTSProviderTest {
    @Test
    fun `parser accepts concatenated streaming json objects`() {
        val chunks = parseAgentPlanTtsResponse(
            """{"code":0,"data":"AQI="}
               {"code":0,"message":"done"}{"code":0,"data":"Aw=="}""".trimIndent()
        )

        assertArrayEquals(byteArrayOf(1, 2), chunks[0])
        assertArrayEquals(byteArrayOf(3), chunks[1])
    }

    @Test
    fun `serialized setting contains neither runtime key nor service url`() {
        val setting: TTSProviderSetting = TTSProviderSetting.VolcengineAgentPlan(
            providerId = "main-provider",
            apiKey = "secret-agent-plan-key",
        )

        val encoded = Json.encodeToString(setting)

        assertFalse(encoded.contains("secret-agent-plan-key"))
        assertFalse(encoded.contains("openspeech.bytedance.com"))
        assertTrue(encoded.contains("main-provider"))
    }
}
