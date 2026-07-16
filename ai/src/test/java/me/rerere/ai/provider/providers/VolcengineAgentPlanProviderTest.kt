package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolcengineAgentPlanProviderTest {
    @Test
    fun `listModels uses bundled catalog without a network request`() = runBlocking {
        var requestCount = 0
        val client = OkHttpClient.Builder()
            .addInterceptor {
                requestCount++
                error("Agent Plan model discovery must not use the network")
            }
            .build()
        val provider = VolcengineAgentPlanProvider(client)

        val models = provider.listModels(ProviderSetting.VolcengineAgentPlan())

        assertEquals(0, requestCount)
        assertTrue(models.any { it.modelId == "ark-code-latest" })
        assertTrue(models.any { it.modelId == "doubao-seed-2.0-code" })
    }

    @Test
    fun `runtime adapter forces the Responses API on the Agent Plan endpoint`() {
        val provider = VolcengineAgentPlanProvider(OkHttpClient())
        val setting = ProviderSetting.VolcengineAgentPlan(
            apiKey = "agent-plan-key",
            baseUrl = "https://example.com/v1",
        )

        val openAISetting = with(provider) { setting.asOpenAISetting() }

        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3", openAISetting.baseUrl)
        assertEquals("agent-plan-key", openAISetting.apiKey)
        assertTrue(openAISetting.useResponseApi)
    }
}
