package me.rerere.asr.providers

import me.rerere.asr.ASRProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class DashScopeASRControllerTest {
    @Test
    fun `default endpoint uses realtime protocol and appends model`() {
        val provider = ASRProviderSetting.DashScope()

        assertEquals(
            "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime",
            provider.websocketEndpoint(),
        )
    }

    @Test
    fun `custom endpoint keeps query parameters and existing model`() {
        val withQuery = ASRProviderSetting.DashScope(
            websocketUrl = "wss://workspace.example/api-ws/v1/realtime?heartbeat=true",
            model = "qwen3-asr-flash-realtime-2026-02-10",
        )
        val withModel = ASRProviderSetting.DashScope(
            websocketUrl = "wss://workspace.example/api-ws/v1/realtime?model=custom",
        )

        assertEquals(
            "wss://workspace.example/api-ws/v1/realtime?heartbeat=true&model=qwen3-asr-flash-realtime-2026-02-10",
            withQuery.websocketEndpoint(),
        )
        assertEquals(
            "wss://workspace.example/api-ws/v1/realtime?model=custom",
            withModel.websocketEndpoint(),
        )
    }
}
