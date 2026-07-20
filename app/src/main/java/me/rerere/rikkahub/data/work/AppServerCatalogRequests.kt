package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keeps large remote marketplace catalogs off the latency-sensitive Work WebSocket path. */
object AppServerCatalogRequests {
    fun installedPluginCatalog(repositoryPath: String) = buildJsonObject {
        put("cwds", buildJsonArray { add(JsonPrimitive(repositoryPath)) })
        put("marketplaceKinds", buildJsonArray {
            add(JsonPrimitive("local"))
            add(JsonPrimitive("workspace-directory"))
        })
    }
}
