package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.workflow.codex.CodexRuntimeSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServerRuntimeMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `keeps server-only models and their controls`() {
        val result = json.parseToJsonElement(
            """{"data":[{"id":"future-model","model":"future-model","displayName":"Future","description":"new","hidden":false,"supportedReasoningEfforts":[{"reasoningEffort":"high","description":"deep"}],"defaultReasoningEffort":"high","inputModalities":["text","image"],"serviceTiers":[{"id":"priority","name":"Fast","description":"fast"}],"defaultServiceTier":null,"isDefault":false}]}"""
        )

        val model = AppServerRuntimeMapper.models(result).single()

        assertEquals("future-model", model.id)
        assertEquals("high", model.defaultReasoningEffort)
        assertEquals("priority", model.serviceTiers.single().id)
        assertTrue("image" in model.inputModalities)
    }

    @Test
    fun `maps settings and token notifications independently`() {
        val settings = AppServerRuntimeMapper.applyNotification(
            CodexRuntimeSettingsState(),
            AppServerNotification(
                "thread/settings/updated",
                json.parseToJsonElement(
                    """{"threadId":"t","threadSettings":{"model":"gpt-x","effort":"xhigh","serviceTier":"priority","sandboxPolicy":{"type":"workspaceWrite"},"activePermissionProfile":null}}"""
                ),
            ),
        )
        val usage = AppServerRuntimeMapper.applyNotification(
            settings,
            AppServerNotification(
                "thread/tokenUsage/updated",
                json.parseToJsonElement(
                    """{"threadId":"t","turnId":"u","tokenUsage":{"total":{"totalTokens":1234},"modelContextWindow":200000}}"""
                ),
            ),
        )

        assertEquals("gpt-x", usage.model)
        assertEquals("xhigh", usage.effort)
        assertEquals("default", usage.permissions)
        assertEquals(1234L, usage.usedTokens)
        assertEquals(200000L, usage.contextWindow)
    }

    @Test
    fun `maps installed plugins and accessible apps from official lists`() {
        val plugins = AppServerRuntimeMapper.plugins(
            json.parseToJsonElement(
                """{"marketplaces":[{"plugins":[{"id":"p1","name":"figma","installed":true,"enabled":true,"availability":{"type":"AVAILABLE"},"interface":{"displayName":"Figma","shortDescription":"Design"}}]}]}"""
            )
        )
        val apps = AppServerRuntimeMapper.apps(
            json.parseToJsonElement(
                """{"data":[{"id":"a1","name":"drive","description":"Files","isAccessible":true,"isEnabled":true}]}"""
            )
        )

        assertEquals("figma", plugins.single().name)
        assertTrue(plugins.single().installed)
        assertEquals("drive", apps.single().name)
        assertTrue(apps.single().isAccessible)
    }
}
