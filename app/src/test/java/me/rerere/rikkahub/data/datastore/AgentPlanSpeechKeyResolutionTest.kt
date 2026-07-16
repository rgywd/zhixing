package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPlanSpeechKeyResolutionTest {
    @Test
    fun `tts and asr reuse key from main Agent Plan provider`() {
        val mainProvider = ProviderSetting.VolcengineAgentPlan(apiKey = "one-shared-key")
        val tts = TTSProviderSetting.VolcengineAgentPlan(
            providerId = mainProvider.id.toString(),
        )
        val asr = ASRProviderSetting.VolcengineAgentPlan(
            providerId = mainProvider.id.toString(),
        )
        val settings = Settings(
            providers = listOf(mainProvider),
            ttsProviders = listOf(tts),
            selectedTTSProviderId = tts.id,
            asrProviders = listOf(asr),
            selectedASRProviderId = asr.id,
        )

        val resolvedTts = settings.getSelectedTTSProvider() as TTSProviderSetting.VolcengineAgentPlan
        val resolvedAsr = settings.getSelectedASRProvider() as ASRProviderSetting.VolcengineAgentPlan

        assertEquals("one-shared-key", resolvedTts.apiKey)
        assertEquals("one-shared-key", resolvedAsr.apiKey)
    }
}
