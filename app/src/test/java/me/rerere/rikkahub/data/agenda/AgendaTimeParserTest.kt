package me.rerere.rikkahub.data.agenda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AgendaTimeParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 7, 22, 10, 0, 0, 0, zone)

    @Test
    fun parsesChineseQuickInputAndRemovesTimePhrase() {
        val result = parseAgendaQuickInput("明天 20:30 给父母打电话", now)

        assertEquals("给父母打电话", result.title)
        assertEquals(
            ZonedDateTime.of(2026, 7, 23, 20, 30, 0, 0, zone).toInstant().toEpochMilli(),
            result.dueAt,
        )
    }

    @Test
    fun mapsAfternoonHourTo24HourClock() {
        val result = parseAgendaQuickInput("后天下午3点交报告", now)

        assertEquals("交报告", result.title)
        assertEquals(
            ZonedDateTime.of(2026, 7, 24, 15, 0, 0, 0, zone).toInstant().toEpochMilli(),
            result.dueAt,
        )
    }

    @Test
    fun leavesPlainTitleWithoutInventingTime() {
        val result = parseAgendaQuickInput("整理产品方案", now)

        assertEquals("整理产品方案", result.title)
        assertNull(result.dueAt)
    }

    @Test
    fun parsesExplicitLocalDateTime() {
        assertEquals(
            ZonedDateTime.of(2026, 7, 23, 8, 15, 0, 0, zone).toInstant().toEpochMilli(),
            parseAgendaTime("2026-07-23 08:15", zone),
        )
    }
}
