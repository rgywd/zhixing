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

private const val DASH_SCOPE_ASR_TYPE = "dashscope"
private const val OLD_DASH_SCOPE_ASR_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
private const val NEW_DASH_SCOPE_ASR_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"

class PreferenceStoreV6Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 6
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.ASR_PROVIDERS] = migrateDashScopeAsrProviders(
            prefs[SettingsStore.ASR_PROVIDERS] ?: "[]"
        )
        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun migrateDashScopeAsrProviders(json: String): String = runCatching {
    val providers = JsonInstant.parseToJsonElement(json).jsonArray.map { element ->
        val item = element as? JsonObject ?: return@map element
        if (item.stringValue("type") != DASH_SCOPE_ASR_TYPE ||
            item.stringValue("websocketUrl") != OLD_DASH_SCOPE_ASR_URL
        ) {
            return@map item
        }
        JsonObject(item.toMutableMap().apply {
            put("websocketUrl", JsonPrimitive(NEW_DASH_SCOPE_ASR_URL))
        })
    }
    JsonInstant.encodeToString(JsonArray(providers))
}.getOrDefault(json)

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull
