package me.rerere.rikkahub.data.work

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PhoneWorkCatalogStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): PhoneWorkCatalog = preferences.getString(KEY_CATALOG, null)
        ?.let { runCatching { json.decodeFromString<PhoneWorkCatalog>(it) }.getOrNull() }
        ?: PhoneWorkCatalog()

    fun save(catalog: PhoneWorkCatalog) {
        preferences.edit().putString(KEY_CATALOG, json.encodeToString(catalog)).apply()
    }

    private companion object {
        const val PREFERENCES = "phone_work_catalog"
        const val KEY_CATALOG = "catalog"
    }
}
