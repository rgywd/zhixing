package me.rerere.rikkahub.data.agenda

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ParsedAgendaInput(
    val title: String,
    val dueAt: Long?,
)

fun parseAgendaTime(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long {
    val text = raw.trim()
    text.toLongOrNull()?.let { return it }
    runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
    runCatching { return Instant.parse(text).toEpochMilli() }
    runCatching { return LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }
    runCatching {
        return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone).toInstant().toEpochMilli() }
    error("无法识别时间 '$raw'，请使用 ISO-8601 或 yyyy-MM-dd HH:mm")
}

fun parseAgendaQuickInput(
    raw: String,
    now: ZonedDateTime = ZonedDateTime.now(),
): ParsedAgendaInput {
    val trimmed = raw.trim()
    val pattern = Regex("(今天|明天|后天)\\s*(上午|下午|晚上)?\\s*(\\d{1,2})[点:：](\\d{1,2})?")
    val match = pattern.find(trimmed) ?: return ParsedAgendaInput(trimmed, null)
    val dayOffset = when (match.groupValues[1]) {
        "明天" -> 1L
        "后天" -> 2L
        else -> 0L
    }
    val period = match.groupValues[2]
    var hour = match.groupValues[3].toInt().coerceIn(0, 23)
    if ((period == "下午" || period == "晚上") && hour in 1..11) hour += 12
    val minute = match.groupValues[4].toIntOrNull()?.coerceIn(0, 59) ?: 0
    val dueAt = now.toLocalDate()
        .plusDays(dayOffset)
        .atTime(hour, minute)
        .atZone(now.zone)
        .toInstant()
        .toEpochMilli()
    val title = trimmed.removeRange(match.range).trim(' ', '，', ',', '。').ifBlank { trimmed }
    return ParsedAgendaInput(title, dueAt)
}
