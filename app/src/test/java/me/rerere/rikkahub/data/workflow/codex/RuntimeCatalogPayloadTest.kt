package me.rerere.rikkahub.data.workflow.codex

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCatalogPayloadTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes dynamic model permissions skill plugin and app catalogs`() {
        val payload = json.decodeFromString<RuntimeCatalogPayload>(
            """
            {
              "machineId":"machine_1","cwd":"C:/repo","generatedAt":1,
              "models":[{"id":"gpt-5.4","model":"gpt-5.4","displayName":"GPT-5.4","description":"","isDefault":true,"hidden":false,"defaultReasoningEffort":"high","supportedReasoningEfforts":[{"reasoningEffort":"max","description":"Maximum"}],"serviceTiers":[{"id":"priority","name":"Fast","description":""}]}],
              "permissionProfiles":[{"id":":workspace","allowed":true}],
              "skills":[{"name":"review","path":"C:/skills/review/SKILL.md","description":"Review","enabled":true,"scope":"repo"}],
              "plugins":[{"id":"github","name":"github","installed":true,"enabled":true,"availability":"AVAILABLE"}],
              "apps":[{"id":"drive","name":"Google Drive","isAccessible":true,"isEnabled":true}]
            }
            """.trimIndent()
        )

        assertEquals("max", payload.models.single().supportedReasoningEfforts.single().reasoningEffort)
        assertEquals("priority", payload.models.single().serviceTiers.single().id)
        assertTrue(payload.permissionProfiles.single().allowed)
        assertEquals("review", payload.skills.single().name)
        assertTrue(payload.plugins.single().installed)
        assertTrue(payload.apps.single().isAccessible)
    }

    @Test
    fun `reports confirmed context usage as a bounded percentage`() {
        assertEquals(50, CodexRuntimeSettingsState(usedTokens = 50_000, contextWindow = 100_000).contextPercent)
        assertEquals(100, CodexRuntimeSettingsState(usedTokens = 120_000, contextWindow = 100_000).contextPercent)
    }
}
