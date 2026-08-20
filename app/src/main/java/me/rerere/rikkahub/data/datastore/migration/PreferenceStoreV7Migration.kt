package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

class PreferenceStoreV7Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 7
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.ASSISTANTS] = migrateAssistantReasoningLevels(
            prefs[SettingsStore.ASSISTANTS] ?: "[]"
        )
        prefs[SettingsStore.VERSION] = 7
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun migrateAssistantReasoningLevels(json: String): String = runCatching {
    val assistants = JsonInstant.parseToJsonElement(json).jsonArray.map { element ->
        val assistant = element as? JsonObject ?: return@map element
        if (assistant["reasoningLevel"]?.jsonPrimitive?.contentOrNull != "auto") {
            return@map assistant
        }
        JsonObject(assistant.toMutableMap().apply {
            put("reasoningLevel", JsonPrimitive("medium"))
        })
    }
    JsonInstant.encodeToString(JsonArray(assistants))
}.getOrDefault(json)
