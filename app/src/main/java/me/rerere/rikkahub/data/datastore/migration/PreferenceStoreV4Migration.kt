package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

private const val RETIRED_PROVIDER_TYPE = "volcengine_agent_plan"

class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        val providerCleanup = removeRetiredProviderEntries(prefs[SettingsStore.PROVIDERS] ?: "[]")
        val ttsCleanup = removeRetiredProviderEntries(prefs[SettingsStore.TTS_PROVIDERS] ?: "[]")
        val asrCleanup = removeRetiredProviderEntries(prefs[SettingsStore.ASR_PROVIDERS] ?: "[]")

        prefs[SettingsStore.PROVIDERS] = providerCleanup.json
        prefs[SettingsStore.TTS_PROVIDERS] = ttsCleanup.json
        prefs[SettingsStore.ASR_PROVIDERS] = asrCleanup.json

        clearIfRetired(prefs, SettingsStore.SELECTED_TTS_PROVIDER, ttsCleanup.removedIds)
        clearIfRetired(prefs, SettingsStore.SELECTED_ASR_PROVIDER, asrCleanup.removedIds)

        val removedModelIds = providerCleanup.removedModelIds
        listOf(
            SettingsStore.SELECT_MODEL,
            SettingsStore.FAST_MODEL,
            SettingsStore.TITLE_MODEL,
            SettingsStore.TRANSLATE_MODEL,
            SettingsStore.SUGGESTION_MODEL,
            SettingsStore.IMAGE_GENERATION_MODEL,
            SettingsStore.OCR_MODEL,
        ).forEach { key -> clearIfRetired(prefs, key, removedModelIds) }

        prefs[SettingsStore.ASSISTANTS] = clearRetiredAssistantModels(
            prefs[SettingsStore.ASSISTANTS] ?: "[]",
            removedModelIds,
        )
        prefs[SettingsStore.VERSION] = 4
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal data class RetiredProviderCleanup(
    val json: String,
    val removedIds: Set<String>,
    val removedModelIds: Set<String>,
)

internal fun removeRetiredProviderEntries(json: String): RetiredProviderCleanup {
    return runCatching {
        val removedIds = mutableSetOf<String>()
        val removedModelIds = mutableSetOf<String>()
        val retained = JsonInstant.parseToJsonElement(json).jsonArray.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull element
            if (item.typeName() == RETIRED_PROVIDER_TYPE) {
                item.stringValue("id")?.let(removedIds::add)
                (item["models"] as? JsonArray).orEmpty().forEach { model ->
                    (model as? JsonObject)?.stringValue("id")?.let(removedModelIds::add)
                }
                null
            } else {
                item.withoutRetiredProviderOverrides()
            }
        }
        RetiredProviderCleanup(
            json = JsonInstant.encodeToString(JsonArray(retained)),
            removedIds = removedIds,
            removedModelIds = removedModelIds,
        )
    }.getOrElse {
        RetiredProviderCleanup(json, emptySet(), emptySet())
    }
}

internal fun clearRetiredAssistantModels(json: String, removedModelIds: Set<String>): String {
    if (removedModelIds.isEmpty()) return json
    return runCatching {
        val assistants = JsonInstant.parseToJsonElement(json).jsonArray.map { element ->
            val assistant = element as? JsonObject ?: return@map element
            if (assistant.stringValue("chatModelId") !in removedModelIds) return@map assistant
            JsonObject(assistant.toMutableMap().apply { remove("chatModelId") })
        }
        JsonInstant.encodeToString(JsonArray(assistants))
    }.getOrDefault(json)
}

private fun JsonObject.withoutRetiredProviderOverrides(): JsonObject {
    val models = this["models"] as? JsonArray ?: return this
    val cleanedModels = JsonArray(models.map { element ->
        val model = element as? JsonObject ?: return@map element
        val providerOverride = model["providerOverwrite"] as? JsonObject
        if (providerOverride?.typeName() != RETIRED_PROVIDER_TYPE) return@map model
        JsonObject(model.toMutableMap().apply { remove("providerOverwrite") })
    })
    return JsonObject(toMutableMap().apply { put("models", cleanedModels) })
}

private fun JsonObject.typeName(): String? = stringValue("type")

private fun JsonObject.stringValue(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull
}

private fun clearIfRetired(
    prefs: androidx.datastore.preferences.core.MutablePreferences,
    key: Preferences.Key<String>,
    retiredIds: Set<String>,
) {
    if (prefs[key] in retiredIds) prefs.remove(key)
}
