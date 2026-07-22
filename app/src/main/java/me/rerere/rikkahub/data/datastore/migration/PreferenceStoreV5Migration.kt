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

private const val VOLCENGINE_TTS_TYPE = "volcengine_tts"
private const val VOLCENGINE_ASR_TYPE = "volcengine"
private const val OLD_XIAOHE_VOICE = "zh_female_xiaohe_jupiter_bigtts"
private const val NEW_XIAOHE_VOICE = "zh_female_xiaohe_uranus_bigtts"
private const val OLD_VIVI_VOICE = "zh_female_vv_jupiter_bigtts"
private const val NEW_VIVI_VOICE = "zh_female_vv_uranus_bigtts"
private const val OLD_ASR_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"
private const val NEW_ASR_URL =
    "wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async"

class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.TTS_PROVIDERS] = migrateVolcengineTtsProviders(
            prefs[SettingsStore.TTS_PROVIDERS] ?: "[]"
        )
        prefs[SettingsStore.ASR_PROVIDERS] = migrateVolcengineAsrProviders(
            prefs[SettingsStore.ASR_PROVIDERS] ?: "[]"
        )
        prefs[SettingsStore.VERSION] = 5
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun migrateVolcengineTtsProviders(json: String): String = migrateProviderArray(json) { item ->
    if (item.stringValue("type") != VOLCENGINE_TTS_TYPE) return@migrateProviderArray item
    val voice = when (item.stringValue("voice")) {
        OLD_XIAOHE_VOICE -> NEW_XIAOHE_VOICE
        OLD_VIVI_VOICE -> NEW_VIVI_VOICE
        else -> return@migrateProviderArray item
    }
    JsonObject(item.toMutableMap().apply { put("voice", JsonPrimitive(voice)) })
}

internal fun migrateVolcengineAsrProviders(json: String): String = migrateProviderArray(json) { item ->
    if (item.stringValue("type") != VOLCENGINE_ASR_TYPE ||
        item.stringValue("websocketUrl") != OLD_ASR_URL
    ) {
        return@migrateProviderArray item
    }
    JsonObject(item.toMutableMap().apply {
        put("websocketUrl", JsonPrimitive(NEW_ASR_URL))
    })
}

private fun migrateProviderArray(
    json: String,
    transform: (JsonObject) -> JsonObject,
): String = runCatching {
    val providers = JsonInstant.parseToJsonElement(json).jsonArray.map { element ->
        val item = element as? JsonObject ?: return@map element
        transform(item)
    }
    JsonInstant.encodeToString(JsonArray(providers))
}.getOrDefault(json)

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull
