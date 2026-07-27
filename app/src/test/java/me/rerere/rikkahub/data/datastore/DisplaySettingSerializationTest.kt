package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySettingSerializationTest {
    @Test
    fun `missing agenda drawer gesture preference defaults to enabled`() {
        val setting = JsonInstant.decodeFromString<DisplaySetting>("{}")

        assertTrue(setting.enableAgendaDrawerGesture)
    }

    @Test
    fun `disabled agenda drawer gesture preference survives serialization`() {
        val encoded = JsonInstant.encodeToString(
            DisplaySetting(enableAgendaDrawerGesture = false)
        )
        val decoded = JsonInstant.decodeFromString<DisplaySetting>(encoded)

        assertFalse(decoded.enableAgendaDrawerGesture)
    }
}
