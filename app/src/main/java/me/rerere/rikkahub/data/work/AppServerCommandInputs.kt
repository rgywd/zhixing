package me.rerere.rikkahub.data.work

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.workflow.codex.RuntimeCatalogPayload

/** Resolves the slash commands shown by Zhixing into official App Server user inputs. */
object AppServerCommandInputs {
    private val commandPattern = Regex("(?:^|[\\s,，。；;!?！？])/(?:\\s*)([A-Za-z0-9_.:-]+)")

    fun names(text: String): Set<String> =
        commandPattern.findAll(text).map { it.groupValues[1] }.toSet()

    fun resolve(text: String, catalog: RuntimeCatalogPayload): List<JsonObject> {
        val commands = names(text)
        if (commands.isEmpty()) return emptyList()

        val claimed = mutableSetOf<String>()
        return buildList {
            catalog.skills
                .filter { it.enabled && it.name in commands && claimed.add(it.name) }
                .forEach { skill ->
                    add(buildJsonObject {
                        put("type", "skill")
                        put("name", skill.name)
                        put("path", skill.path)
                    })
                }
            catalog.plugins
                .filter { it.installed && it.enabled && it.name in commands && claimed.add(it.name) }
                .forEach { plugin ->
                    add(buildJsonObject {
                        put("type", "mention")
                        put("name", plugin.name)
                        put("path", "plugin://${plugin.id}")
                    })
                }
            catalog.apps
                .filter { it.isAccessible && it.isEnabled && it.name in commands && claimed.add(it.name) }
                .forEach { app ->
                    add(buildJsonObject {
                        put("type", "mention")
                        put("name", app.name)
                        put("path", "app://${app.id}")
                    })
                }
        }
    }
}
