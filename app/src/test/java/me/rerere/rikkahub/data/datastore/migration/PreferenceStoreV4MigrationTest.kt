package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PreferenceStoreV4MigrationTest {
    @Test
    fun `retired providers and nested overrides are removed before deserialization`() {
        val cleanup = removeRetiredProviderEntries(
            """
            [
              {
                "type": "openai",
                "id": "kept",
                "models": [
                  {
                    "id": "kept-model",
                    "providerOverwrite": {
                      "type": "volcengine_agent_plan",
                      "id": "override"
                    }
                  }
                ]
              },
              {
                "type": "volcengine_agent_plan",
                "id": "retired",
                "models": [{"id": "retired-model"}]
              }
            ]
            """.trimIndent()
        )

        val providers = JsonInstant.parseToJsonElement(cleanup.json).jsonArray
        assertEquals(1, providers.size)
        assertEquals("kept", providers.single().jsonObject["id"]?.jsonPrimitive?.content)
        assertNull(
            providers.single().jsonObject["models"]
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("providerOverwrite")
        )
        assertEquals(setOf("retired"), cleanup.removedIds)
        assertEquals(setOf("retired-model"), cleanup.removedModelIds)
    }

    @Test
    fun `settings backup migration clears retired selections`() {
        val migrated = SettingsJsonMigrator.migrate(
            """
            {
              "providers": [
                {
                  "type": "volcengine_agent_plan",
                  "id": "retired",
                  "models": [{"id": "retired-model"}]
                }
              ],
              "ttsProviders": [{"type": "volcengine_agent_plan", "id": "retired-tts"}],
              "selectedTTSProviderId": "retired-tts",
              "asrProviders": [{"type": "volcengine_agent_plan", "id": "retired-asr"}],
              "selectedASRProviderId": "retired-asr",
              "chatModelId": "retired-model",
              "assistants": [{"id": "assistant", "chatModelId": "retired-model"}]
            }
            """.trimIndent()
        )

        val root = JsonInstant.parseToJsonElement(migrated).jsonObject
        assertEquals(0, root["providers"]?.jsonArray?.size)
        assertEquals(0, root["ttsProviders"]?.jsonArray?.size)
        assertEquals(0, root["asrProviders"]?.jsonArray?.size)
        assertFalse("selectedTTSProviderId" in root)
        assertFalse("selectedASRProviderId" in root)
        assertEquals(DEFAULT_AUTO_MODEL_ID.toString(), root["chatModelId"]?.jsonPrimitive?.content)
        assertFalse("chatModelId" in root["assistants"]?.jsonArray?.single()?.jsonObject.orEmpty())
    }
}
