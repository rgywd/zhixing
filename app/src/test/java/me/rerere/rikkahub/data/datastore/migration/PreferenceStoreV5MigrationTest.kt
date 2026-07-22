package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceStoreV5MigrationTest {
    @Test
    fun `built in volcengine speech defaults are upgraded`() {
        val tts = migrateVolcengineTtsProviders(
            """[{"type":"volcengine_tts","voice":"zh_female_xiaohe_jupiter_bigtts"}]"""
        )
        val asr = migrateVolcengineAsrProviders(
            """[{"type":"volcengine","websocketUrl":"wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"}]"""
        )

        assertEquals(
            "zh_female_xiaohe_uranus_bigtts",
            JsonInstant.parseToJsonElement(tts).jsonArray.single().jsonObject["voice"]
                ?.jsonPrimitive?.content,
        )
        assertEquals(
            "wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async",
            JsonInstant.parseToJsonElement(asr).jsonArray.single().jsonObject["websocketUrl"]
                ?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `custom volcengine speech settings are preserved`() {
        val tts = """[{"type":"volcengine_tts","voice":"custom-speaker"}]"""
        val asr = """[{"type":"volcengine","websocketUrl":"wss://custom.example/asr"}]"""

        assertEquals(tts, migrateVolcengineTtsProviders(tts))
        assertEquals(asr, migrateVolcengineAsrProviders(asr))
    }

    @Test
    fun `settings backup migration upgrades speech defaults`() {
        val migrated = SettingsJsonMigrator.migrate(
            """
            {
              "ttsProviders": [
                {"type":"volcengine_tts","voice":"zh_female_vv_jupiter_bigtts"}
              ],
              "asrProviders": [
                {"type":"volcengine","websocketUrl":"wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"}
              ]
            }
            """.trimIndent()
        )
        val root = JsonInstant.parseToJsonElement(migrated).jsonObject

        assertEquals(
            "zh_female_vv_uranus_bigtts",
            root["ttsProviders"]?.jsonArray?.single()?.jsonObject?.get("voice")
                ?.jsonPrimitive?.content,
        )
        assertEquals(
            "wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async",
            root["asrProviders"]?.jsonArray?.single()?.jsonObject?.get("websocketUrl")
                ?.jsonPrimitive?.content,
        )
    }
}
