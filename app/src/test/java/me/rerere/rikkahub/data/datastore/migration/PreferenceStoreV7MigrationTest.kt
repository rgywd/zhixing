package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceStoreV7MigrationTest {
    @Test
    fun `legacy assistant auto reasoning is migrated to medium`() {
        val migrated = migrateAssistantReasoningLevels(
            """[{"id":"one","reasoningLevel":"auto"},{"id":"two","reasoningLevel":"high"}]"""
        )
        val assistants = JsonInstant.parseToJsonElement(migrated).jsonArray

        assertEquals("medium", assistants[0].jsonObject["reasoningLevel"]?.jsonPrimitive?.content)
        assertEquals("high", assistants[1].jsonObject["reasoningLevel"]?.jsonPrimitive?.content)
    }

    @Test
    fun `settings backup migration replaces auto without changing other levels`() {
        val migrated = SettingsJsonMigrator.migrate(
            """
            {
              "assistants": [
                {"id":"one","reasoningLevel":"auto"},
                {"id":"two","reasoningLevel":"xhigh"}
              ]
            }
            """.trimIndent()
        )
        val assistants = JsonInstant.parseToJsonElement(migrated).jsonObject["assistants"]?.jsonArray!!

        assertEquals("medium", assistants[0].jsonObject["reasoningLevel"]?.jsonPrimitive?.content)
        assertEquals("xhigh", assistants[1].jsonObject["reasoningLevel"]?.jsonPrimitive?.content)
    }
}
