package me.rerere.rikkahub.data.memory

import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.YearMonth

internal val MEMORY_DOCUMENT_FORMAT_GUIDANCE = """
    Use canonical memory fact formats. Write one durable fact per `- [stated] ` bullet with an explicit subject.
    Use YYYY-MM-DD for exact dates, YYYY-MM for a known year and month, MM-DD for yearly recurring dates without a
    year, and YYYY for standalone years. Use 24-hour HH:mm (or HH:mm:ss only when seconds matter), RFC 3339 with an
    explicit UTC offset for absolute date-times, and IANA zone IDs such as Asia/Shanghai. Use ASCII digits, a decimal
    point, explicit SI units such as 70 kg, 175 cm, 22 °C, and percentages such as 18%. Use BCP 47 language tags such
    as zh-CN and uppercase ISO 4217 codes such as CNY only for non-sensitive currency preferences. Preserve stated
    uncertainty and calendar systems; never invent missing precision. These rules apply to normalized memory content,
    never to exact source quotes.
""".trimIndent().replace("\n", " ")

internal data class MemoryContentFormatIssue(
    val code: String,
    val correction: String,
    val uiMessage: String,
)

internal class MemoryDocumentFormatException(
    val issues: List<MemoryContentFormatIssue>,
) : IllegalArgumentException(issues.joinToString(" ", transform = MemoryContentFormatIssue::correction))

private val localizedDatePattern = Regex(
    """(?<!\d)(?:\d{4}\s*年(?:\s*\d{1,2}\s*月(?:\s*\d{1,2}\s*[日号])?)?|""" +
        """\d{1,2}\s*月\s*\d{1,2}\s*[日号])(?!\d)"""
)
private val slashDatePattern = Regex(
    """(?<!\d)(?:\d{4}[/.]\d{1,2}[/.]\d{1,2}|\d{1,2}/\d{1,2})(?!\d)"""
)
private val fullDateCandidatePattern = Regex("""(?<!\d)\d{4}-\d{1,2}-\d{1,2}(?!\d)""")
private val yearMonthCandidatePattern = Regex("""(?<![\d-])\d{4}-\d{1,2}(?!-\d)(?!\d)""")
private val monthDayCandidatePattern = Regex("""(?<![\d-])\d{1,2}-\d{1,2}(?!\d)""")
private val clockCandidatePattern = Regex("""(?<!\d)\d{1,2}[:：]\d{2}(?:[:：]\d{2})?(?!\d)""")
private val localizedTimePattern = Regex("""(?<!\d)\d{1,2}\s*(?:点|时)(?:\s*\d{1,2}\s*分)?""")
private val localizedMetricPattern = Regex(
    """\d+(?:\.\d+)?\s*(?:公斤|千克|厘米|公分|公里|千米|摄氏度|℃)"""
)
private val spacedPercentPattern = Regex("""\d+(?:\.\d+)?\s+%""")
private val localizedPercentPattern = Regex("""百分之\s*[零〇一二两三四五六七八九十百千万\d.]+""")
private val urlPattern = Regex("""(?i)\b(?:https?://|www\.)[^\s<>()]+""")
private val monthDayContextPattern = Regex("""日期|生日|纪念日|节日|周年|每年|年度|公历|农历|月日""")

internal fun findMemoryContentFormatIssues(content: String): List<MemoryContentFormatIssue> {
    val issues = linkedMapOf<String, MemoryContentFormatIssue>()
    val lintContent = urlPattern.replace(content) { " ".repeat(it.value.length) }

    fun add(code: String, correction: String, uiMessage: String) {
        issues.putIfAbsent(code, MemoryContentFormatIssue(code, correction, uiMessage))
    }

    if (localizedDatePattern.containsMatchIn(lintContent) || slashDatePattern.containsMatchIn(lintContent)) {
        add(
            code = "NON_CANONICAL_DATE",
            correction = "Rewrite dates as YYYY-MM-DD, YYYY-MM, MM-DD, or YYYY without inventing missing precision.",
            uiMessage = "日期建议使用 YYYY-MM-DD、YYYY-MM、MM-DD 或 YYYY。",
        )
    }

    fullDateCandidatePattern.findAll(lintContent).forEach { match ->
        val value = match.value
        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(value) ||
            runCatching { LocalDate.parse(value) }.isFailure
        ) {
            add(
                code = "INVALID_DATE",
                correction = "Use a real zero-padded calendar date in YYYY-MM-DD format.",
                uiMessage = "完整日期必须是有效且补零的 YYYY-MM-DD。",
            )
        }
    }
    yearMonthCandidatePattern.findAll(lintContent).forEach { match ->
        val value = match.value
        if (!Regex("""\d{4}-\d{2}""").matches(value) || runCatching { YearMonth.parse(value) }.isFailure) {
            add(
                code = "INVALID_YEAR_MONTH",
                correction = "Use a real zero-padded year and month in YYYY-MM format.",
                uiMessage = "年月必须是有效且补零的 YYYY-MM。",
            )
        }
    }
    monthDayCandidatePattern.findAll(lintContent)
        .filter { match -> hasMonthDayContext(lintContent, match.range) }
        .forEach { match ->
        val value = match.value
        if (!Regex("""\d{2}-\d{2}""").matches(value) ||
            runCatching { MonthDay.parse("--$value") }.isFailure
        ) {
            add(
                code = "INVALID_MONTH_DAY",
                correction = "Use a real zero-padded yearly recurring date in MM-DD format.",
                uiMessage = "年度重复日期必须是有效且补零的 MM-DD。",
            )
        }
    }

    clockCandidatePattern.findAll(lintContent).forEach { match ->
        val value = match.value
        val canonical = value.replace('：', ':')
        if (value != canonical || canonical.substringBefore(':').length != 2 ||
            runCatching { LocalTime.parse(canonical) }.isFailure
        ) {
            add(
                code = "INVALID_TIME",
                correction = "Use a real zero-padded 24-hour time in HH:mm or HH:mm:ss format.",
                uiMessage = "时间必须使用有效且补零的 24 小时制 HH:mm 或 HH:mm:ss。",
            )
        }
    }
    if (localizedTimePattern.containsMatchIn(lintContent)) {
        add(
            code = "NON_CANONICAL_TIME",
            correction = "Rewrite clock times in zero-padded 24-hour HH:mm or HH:mm:ss format.",
            uiMessage = "钟点建议使用补零的 24 小时制 HH:mm。",
        )
    }
    if (localizedMetricPattern.containsMatchIn(lintContent)) {
        add(
            code = "NON_CANONICAL_UNIT",
            correction = "Rewrite metric values with ASCII digits, a space, and a canonical unit such as " +
                "kg, cm, km, or °C.",
            uiMessage = "物理量建议写成 70 kg、175 cm、5 km 或 22 °C。",
        )
    }
    if (spacedPercentPattern.containsMatchIn(lintContent) || localizedPercentPattern.containsMatchIn(lintContent)) {
        add(
            code = "NON_CANONICAL_PERCENT",
            correction = "Rewrite percentages with ASCII digits and no space before %, for example 18%.",
            uiMessage = "百分比建议写成 18%，数字与 % 之间不留空格。",
        )
    }

    return issues.values.toList()
}

private fun hasMonthDayContext(content: String, range: IntRange): Boolean {
    val contextStart = (range.first - 24).coerceAtLeast(0)
    val contextEnd = (range.last + 25).coerceAtMost(content.length)
    return monthDayContextPattern.containsMatchIn(content.substring(contextStart, contextEnd))
}

internal fun requireCanonicalMemoryContent(content: String) {
    val issues = findMemoryContentFormatIssues(content)
    if (issues.isNotEmpty()) throw MemoryDocumentFormatException(issues)
}
