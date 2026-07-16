package me.rerere.asr

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ASRProviderSettingVolcengineAgentPlanTest {
    @Test
    fun `setting is registered and does not persist runtime key or url`() {
        val setting: ASRProviderSetting = ASRProviderSetting.VolcengineAgentPlan(
            providerId = "main-provider",
            apiKey = "secret-agent-plan-key",
        )

        val encoded = Json.encodeToString(setting)

        assertTrue(ASRProviderSetting.Types.contains(ASRProviderSetting.VolcengineAgentPlan::class))
        assertFalse(encoded.contains("secret-agent-plan-key"))
        assertFalse(encoded.contains("openspeech.bytedance.com"))
        assertTrue(encoded.contains("main-provider"))
    }
}
