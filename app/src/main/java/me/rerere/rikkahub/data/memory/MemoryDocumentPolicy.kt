package me.rerere.rikkahub.data.memory

import me.rerere.rikkahub.data.model.MemoryDocumentSource
import me.rerere.rikkahub.data.model.MemoryDocumentSourceType
import java.util.Locale

internal const val MEMORY_DOCUMENT_CONTENT_LIMIT = 16_384
internal const val MEMORY_DOCUMENT_DESCRIPTION_LIMIT = 240
internal const val MEMORY_DOCUMENT_ALIAS_LIMIT = 10
internal const val MEMORY_DOCUMENT_SOURCE_LIMIT = 8
internal const val MEMORY_DOCUMENT_SOURCE_QUOTE_LIMIT = 512

private val writablePath = Regex(
    "^/(profile|preferences)\\.md$|^/(areas|topics|people)/[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,63}\\.md$"
)
private val sensitiveCategoryPattern = Regex(
    pattern = """
        (?ix)
        (race|ethnicity|racial|种族|族裔|
        religion|religious|faith|宗教|信仰|
        sexual\s+orientation|gender\s+identity|性取向|性倾向|性别认同|
        political\s+(?:view|belief|affiliation|party)|政治立场|政治观点|党派|
        immigration\s+status|visa\s+status|移民身份|
        passport\s+(?:number|no)|national\s+id|social\s+security|身份证|护照号|证件号|
        real[-\s]?time\s+location|current\s+location|实时位置|当前位置|
        bank\s*card|debit\s*card|credit\s*card|card\s*number|银行卡|信用卡|卡号|
        api[\s_-]?key|access[\s_-]?token|refresh[\s_-]?token|bearer\s+[a-z0-9._-]+|password|passwd|密码)
    """.trimIndent(),
)
private val exactFinancialPattern = Regex(
    "(?i)(?:salary|income|net worth|bank balance|工资|收入|资产|净资产|余额|存款).{0,20}" +
        "(?:[$¥￥€£]\\s*\\d|\\d[\\d,.]*\\s*(?:usd|cny|rmb|元|万元|美元|欧元))"
)
private val preferenceControlPattern = Regex(
    "(?i)(never\\s+(?:disagree|challenge|question)|always\\s+agree|do\\s+not\\s+question|" +
        "roleplay\\s+as|pretend\\s+to\\s+be|永远(?:别|不要)(?:反驳|质疑)|永远同意|" +
        "不要质疑|扮演(?:成)?|假装(?:是|成))"
)

internal fun normalizeMemoryPath(raw: String): String {
    val normalized = "/" + raw.trim().replace('\\', '/').trim('/').lowercase(Locale.ROOT)
    require(".." !in normalized && "//" !in normalized && '\u0000' !in normalized) {
        "Memory path must be a normalized absolute path"
    }
    return normalized
}

internal fun requireWritableMemoryPath(raw: String): String = normalizeMemoryPath(raw).also { path ->
    require(writablePath.matches(path)) {
        "Memory path must be /profile.md, /preferences.md, or a Markdown file under /areas, /topics, or /people"
    }
}

internal fun requireValidMemoryDocument(
    path: String,
    name: String,
    description: String,
    aliases: List<String>,
    content: String,
) {
    requireWritableMemoryPath(path)
    require(name.trim().isNotEmpty() && name.length <= 80) { "Memory document name is required" }
    require(description.trim().isNotEmpty() && description.length <= MEMORY_DOCUMENT_DESCRIPTION_LIMIT) {
        "Memory document description is required and must be concise"
    }
    require(aliases.size <= MEMORY_DOCUMENT_ALIAS_LIMIT) { "Too many memory document aliases" }
    require(aliases.all { it.isNotBlank() && it.length <= 80 }) { "Memory aliases must be concise" }
    require(content.length <= MEMORY_DOCUMENT_CONTENT_LIMIT) { "Memory document is too large" }
    requireStatedOnlyBody(content)
    val allDocumentText = listOf(name, description, aliases.joinToString("\n"), content).joinToString("\n")
    require(!sensitiveCategoryPattern.containsMatchIn(allDocumentText)) {
        "Sensitive personal information is not allowed in memory"
    }
    require(!exactFinancialPattern.containsMatchIn(allDocumentText)) {
        "Exact financial figures are not allowed in memory"
    }
    if (normalizeMemoryPath(path) == "/preferences.md") {
        require(!preferenceControlPattern.containsMatchIn(allDocumentText)) {
            "Preferences cannot disable disagreement, critical thinking, or identity boundaries"
        }
    }
}

internal fun requireStatedOnlyBody(content: String) {
    content.lineSequence().forEachIndexed { index, rawLine ->
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
        require(line.startsWith("- [stated] ") && line.length > "- [stated] ".length) {
            "Memory line ${index + 1} must start with '- [stated] '"
        }
    }
}

internal fun requireMemorySources(sources: List<MemoryDocumentSource>, allowDirectUserEdit: Boolean) {
    require(sources.isNotEmpty()) { "Memory writes require at least one source" }
    require(sources.size <= MEMORY_DOCUMENT_SOURCE_LIMIT) { "Too many memory document sources" }
    sources.forEach { source ->
        when (source.type) {
            MemoryDocumentSourceType.CHAT -> require(
                source.conversationId.isNotBlank() &&
                    source.conversationId.length <= 128 &&
                    source.messageId.isNotBlank() &&
                    source.messageId.length <= 128 &&
                    source.quote.trim().length in 2..MEMORY_DOCUMENT_SOURCE_QUOTE_LIMIT
            ) { "Chat memory sources require conversation, message, and exact quote" }

            MemoryDocumentSourceType.USER_EDIT -> require(allowDirectUserEdit) {
                "Only the user-facing editor can create USER_EDIT sources"
            }

            MemoryDocumentSourceType.MIGRATION -> error("Migration sources cannot be used for new memory writes")
        }
    }
}
