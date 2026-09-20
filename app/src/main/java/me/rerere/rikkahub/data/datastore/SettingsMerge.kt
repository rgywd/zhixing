package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant

/** Apply only the UI's actual edits; never overwrite an agent's concurrent configuration change. */
internal fun mergeSettingsSnapshots(base: Settings, current: Settings, edited: Settings): Settings {
    fun merge(before: JsonElement?, live: JsonElement?, next: JsonElement?, field: String): JsonElement? {
        if (next == before) return live
        if (live == before || live == next) return next
        if (field == "revision" || field == "configRevision") return live
        if (before is JsonObject && live is JsonObject && next is JsonObject) return JsonObject(
            (before.keys + live.keys + next.keys).mapNotNull { key ->
                merge(before[key], live[key], next[key], key)?.let { key to it }
            }.toMap(),
        )
        if (field == "assistants" && before is JsonArray && live is JsonArray && next is JsonArray) {
            fun JsonArray.byId() = associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
            val b = before.byId(); val c = live.byId(); val n = next.byId()
            return JsonArray((n.keys + c.keys + b.keys).mapNotNull { id -> merge(b[id], c[id], n[id], "assistant") })
        }
        throw IllegalArgumentException("SETTINGS_REVISION_CONFLICT")
    }
    return JsonInstant.decodeFromJsonElement(checkNotNull(merge(
        JsonInstant.encodeToJsonElement(base), JsonInstant.encodeToJsonElement(current),
        JsonInstant.encodeToJsonElement(edited), "settings",
    )))
}
