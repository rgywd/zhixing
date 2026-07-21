package me.rerere.tts.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderSettingVolcengineTest {
    @Test
    fun defaultsMatchAgentPlanContract() {
        val setting = TTSProviderSetting.Volcengine()

        assertEquals("seed-tts-2.0", setting.model)
        assertEquals(
            "wss://openspeech.bytedance.com/api/v3/plan/tts/unidirectional/stream",
            setting.baseUrl,
        )
        assertEquals("zh_female_xiaohe_jupiter_bigtts", setting.voice)
        assertTrue(TTSProviderSetting.Types.contains(TTSProviderSetting.Volcengine::class))
    }

    @Test
    fun serializesWithDedicatedType() {
        val setting = TTSProviderSetting.Volcengine(apiKey = "test-key")
        val encoded = Json.encodeToString(TTSProviderSetting.serializer(), setting)
        val decoded = Json.decodeFromString(TTSProviderSetting.serializer(), encoded)

        assertTrue(encoded.contains("volcengine_tts"))
        assertEquals(setting, decoded)
    }
}
