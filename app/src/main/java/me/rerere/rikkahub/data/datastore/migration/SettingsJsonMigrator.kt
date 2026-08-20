package me.rerere.rikkahub.data.datastore.migration

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "SettingsJsonMigrator"

/**
 * 对备份文件中的 settings.json 应用与 DataStore migration 相同的迁移逻辑。
 *
 * DataStore migration 作用于分散的 key-value 存储，而备份文件中的 settings.json
 * 是整个 [me.rerere.rikkahub.data.datastore.Settings] 对象的序列化结果。
 * 此工具类负责在反序列化前对旧格式的 JSON 执行等价的迁移操作。
 */
object SettingsJsonMigrator {

    /**
     * 对 settings JSON 字符串依次应用所有版本的迁移。
     * 若发生异常则返回原始 JSON，不中断恢复流程。
     */
    fun migrate(settingsJson: String): String {
        return runCatching {
            val root = JsonInstant.parseToJsonElement(settingsJson).jsonObject.toMutableMap()

            // V1: 修复 mcpServers 中全限定类名的 type 字段
            root["mcpServers"]?.let { element ->
                val migrated = migrateMcpServersJson(JsonInstant.encodeToString(element))
                root["mcpServers"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V2: 修复 assistants 中 UIMessagePart 的 type 字段
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantsJson(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V3: 将 assistants 中内嵌的 quickMessages 提取为全局 quickMessages
            root["assistants"]?.let { element ->
                val (migratedAssistants, extractedQuickMessages) =
                    migrateAssistantsQuickMessages(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migratedAssistants)

                if (extractedQuickMessages.isNotEmpty()) {
                    val existing = root["quickMessages"]
                    val existingArray = existing?.let {
                        runCatching { JsonInstant.parseToJsonElement(JsonInstant.encodeToString(it)) as? JsonArray }.getOrNull()
                    } ?: JsonArray(emptyList())
                    val existingIds = existingArray.mapNotNull {
                        (it as? JsonObject)?.get("id")?.toString()?.trim('"')
                    }.toSet()
                    val merged = JsonArray(
                        existingArray + extractedQuickMessages.filter { e ->
                            val id = (e as? JsonObject)?.get("id")?.toString()?.trim('"')
                            id != null && id !in existingIds
                        }
                    )
                    root["quickMessages"] = merged
                }
            }

            // V4: 删除已下线的提供商及其语音配置，并清理已失效的模型选择。
            val providerCleanup = root["providers"]?.let { element ->
                removeRetiredProviderEntries(JsonInstant.encodeToString(element))
            }
            providerCleanup?.let { cleanup ->
                root["providers"] = JsonInstant.parseToJsonElement(cleanup.json)
            }
            val ttsCleanup = root["ttsProviders"]?.let { element ->
                removeRetiredProviderEntries(JsonInstant.encodeToString(element))
            }
            ttsCleanup?.let { cleanup ->
                root["ttsProviders"] = JsonInstant.parseToJsonElement(cleanup.json)
                root.removeIfValueIn("selectedTTSProviderId", cleanup.removedIds)
            }
            val asrCleanup = root["asrProviders"]?.let { element ->
                removeRetiredProviderEntries(JsonInstant.encodeToString(element))
            }
            asrCleanup?.let { cleanup ->
                root["asrProviders"] = JsonInstant.parseToJsonElement(cleanup.json)
                root.removeIfValueIn("selectedASRProviderId", cleanup.removedIds)
            }

            providerCleanup?.removedModelIds.orEmpty().let { removedModelIds ->
                if (removedModelIds.isNotEmpty()) {
                    listOf("chatModelId", "fastModelId", "translateModeId").forEach { key ->
                        if (root.stringValue(key) in removedModelIds) {
                            root[key] = JsonPrimitive(DEFAULT_AUTO_MODEL_ID.toString())
                        }
                    }
                    listOf(
                        "titleModelId",
                        "suggestionModelId",
                        "imageGenerationModelId",
                        "ocrModelId",
                    ).forEach { key -> root.removeIfValueIn(key, removedModelIds) }
                    root["assistants"]?.let { assistants ->
                        val cleaned = clearRetiredAssistantModels(
                            JsonInstant.encodeToString(assistants),
                            removedModelIds,
                        )
                        root["assistants"] = JsonInstant.parseToJsonElement(cleaned)
                    }
                }
            }

            // V5: 修复火山引擎 Agent Plan 语音默认值；只迁移曾经内置的旧值，
            // 不覆盖用户自行填写的音色或 WebSocket 地址。
            root["ttsProviders"]?.let { element ->
                val migrated = migrateVolcengineTtsProviders(
                    JsonInstant.encodeToString(element)
                )
                root["ttsProviders"] = JsonInstant.parseToJsonElement(migrated)
            }
            root["asrProviders"]?.let { element ->
                val migrated = migrateVolcengineAsrProviders(
                    JsonInstant.encodeToString(element)
                )
                root["asrProviders"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V6: DashScope Qwen-ASR-Realtime 已使用 Realtime 事件协议，
            // 仅把旧内置 inference 地址迁移到对应的 realtime 地址。
            root["asrProviders"]?.let { element ->
                val migrated = migrateDashScopeAsrProviders(
                    JsonInstant.encodeToString(element)
                )
                root["asrProviders"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V7: 普通 Chat 取消“自动”档，旧助手显式迁移到新的默认“中等”。
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantReasoningLevels(
                    JsonInstant.encodeToString(element)
                )
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            JsonInstant.encodeToString(JsonObject(root))
        }.onFailure {
            Log.e(TAG, "migrate: Failed to migrate settings JSON, using original", it)
        }.getOrDefault(settingsJson)
    }
}

private fun MutableMap<String, kotlinx.serialization.json.JsonElement>.removeIfValueIn(
    key: String,
    values: Set<String>,
) {
    if (stringValue(key) in values) remove(key)
}

private fun Map<String, kotlinx.serialization.json.JsonElement>.stringValue(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull
}
