package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthGlanceCardTest {
    @Test
    fun `steps are grouped with thousands separators`() {
        assertEquals("12,345", formatGlanceSteps("12345"))
        assertEquals("0", formatGlanceSteps("0"))
        assertEquals("--", formatGlanceSteps(null))
        assertEquals("--", formatGlanceSteps("not-a-number"))
    }

    @Test
    fun `sleep minutes collapse to hours and minutes`() {
        assertEquals("--", formatGlanceSleepMinutes(null))
        assertEquals("0 分钟", formatGlanceSleepMinutes("0"))
        assertEquals("45 分钟", formatGlanceSleepMinutes("45"))
        assertEquals("7 小时", formatGlanceSleepMinutes("420"))
        assertEquals("7小时30分", formatGlanceSleepMinutes("450"))
    }

    @Test
    fun `heart rate keeps the bpm unit`() {
        assertEquals("--", formatGlanceHeartRate(null))
        assertEquals("72 bpm", formatGlanceHeartRate("72"))
        assertEquals("72.5 bpm", formatGlanceHeartRate("72.50"))
    }
}
