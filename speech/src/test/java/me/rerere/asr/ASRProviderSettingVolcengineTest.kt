package me.rerere.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class ASRProviderSettingVolcengineTest {
    @Test
    fun defaultsMatchAgentPlanContract() {
        val setting = ASRProviderSetting.Volcengine()

        assertEquals(
            "wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async",
            setting.websocketUrl,
        )
        assertEquals("volc.seedasr.sauc.duration", setting.resourceId)
    }
}
