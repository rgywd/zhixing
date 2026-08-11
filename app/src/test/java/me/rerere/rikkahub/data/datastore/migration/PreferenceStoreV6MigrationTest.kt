package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceStoreV6MigrationTest {
    @Test
    fun `built in DashScope inference endpoint is upgraded`() {
        val migrated = migrateDashScopeAsrProviders(
            """[{"type":"dashscope","websocketUrl":"wss://dashscope.aliyuncs.com/api-ws/v1/inference"}]"""
        )

        assertEquals(
            "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
            JsonInstant.parseToJsonElement(migrated).jsonArray.single().jsonObject["websocketUrl"]
                ?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `custom and non DashScope endpoints are preserved`() {
        val custom = """[{"type":"dashscope","websocketUrl":"wss://workspace.example/realtime"}]"""
        val other = """[{"type":"volcengine","websocketUrl":"wss://dashscope.aliyuncs.com/api-ws/v1/inference"}]"""

        assertEquals(custom, migrateDashScopeAsrProviders(custom))
        assertEquals(other, migrateDashScopeAsrProviders(other))
    }

    @Test
    fun `settings backup migration upgrades DashScope default`() {
        val migrated = SettingsJsonMigrator.migrate(
            """
            {
              "asrProviders": [
                {
                  "type": "dashscope",
                  "websocketUrl": "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
                }
              ]
            }
            """.trimIndent()
        )
        val endpoint = JsonInstant.parseToJsonElement(migrated).jsonObject["asrProviders"]
            ?.jsonArray?.single()?.jsonObject?.get("websocketUrl")?.jsonPrimitive?.content

        assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/realtime", endpoint)
    }
}
