package me.rerere.rikkahub.data.memory

import me.rerere.rikkahub.data.model.MemoryDocumentSource
import me.rerere.rikkahub.data.model.MemoryDocumentSourceType
import java.util.Locale

internal const val MEMORY_DOCUMENT_CONTENT_LIMIT = 16_384
internal const val MEMORY_DOCUMENT_DESCRIPTION_LIMIT = 240
internal const val MEMORY_DOCUMENT_ALIAS_LIMIT = 10
internal const val MEMORY_DOCUMENT_SOURCE_LIMIT = 32
internal const val MEMORY_DOCUMENT_SOURCE_QUOTE_LIMIT = 512

private val writablePath = Regex(
    "^/(profile|preferences)\\.md$|^/(areas|topics|people|archive)/" +
        "[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,95}\\.md$"
)
private val secretAssignmentPattern = Regex(
    pattern = """
        (?ix)
        (?:api[\s_-]?key|access[\s_-]?token|refresh[\s_-]?token|password|passwd|密码)
        \s*(?:is|是|为|:|=)\s*["']?[a-z0-9._~+/@-]{4,}
    """.trimIndent(),
)
private val standaloneCredentialPattern = Regex(
    pattern = """
        (?ix)
        (?:bearer\s+[a-z0-9._~+/-]{8,}|
        (?:sk|rk|gh[opsu]|xox[baprs])[-_][a-z0-9_-]{8,}|
        -----begin\s+(?:rsa\s+|ec\s+|openssh\s+)?private\s+key-----)
    """.trimIndent(),
)
private val cardNumberPattern = Regex(
    "(?ix)(?:bank\\s*card|debit\\s*card|credit\\s*card|card\\s*(?:number|no)|银行卡|信用卡|卡号)" +
        ".{0,16}\\d(?:[-\\s]?\\d){11,18}"
)
private val identityNumberPattern = Regex(
    "(?ix)(?:passport\\s*(?:number|no)|national\\s*id|social\\s*security|身份证|护照号|证件号)" +
        ".{0,16}[a-z0-9](?:[-\\s]?[a-z0-9]){5,24}"
)
private val exactPersonalFinancialPattern = Regex(
    "(?ix)(?:salary|income|net\\s*worth|bank\\s*balance|household\\s*(?:spending|expenses)|" +
        "工资|月薪|收入|净资产|账户?余额|存款|家庭(?:消费|支出)).{0,24}" +
        "(?:[\\u0024¥￥€£]\\s*\\d[\\d,.]*|\\d[\\d,.]*\\s*(?:usd|cny|rmb|元|万元|美元|欧元))|" +
        "(?:[\\u0024¥￥€£]\\s*\\d[\\d,.]*|\\d[\\d,.]*\\s*(?:usd|cny|rmb|元|万元|美元|欧元))" +
        ".{0,24}(?:salary|income|net\\s*worth|bank\\s*balance|household\\s*(?:spending|expenses)|" +
        "工资|月薪|收入|净资产|账户?余额|存款|家庭(?:消费|支出))"
)

internal class MemoryDocumentPolicyException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

internal fun normalizeMemoryPath(raw: String): String {
    val normalized = "/" + raw.trim().replace('\\', '/').trim('/').lowercase(Locale.ROOT)
    require(".." !in normalized && "//" !in normalized && '\u0000' !in normalized) {
        "Memory path must be a normalized absolute path"
    }
    return normalized
}

internal fun requireWritableMemoryPath(raw: String): String = normalizeMemoryPath(raw).also { path ->
    require(writablePath.matches(path)) {
        "Memory path must be /profile.md, /preferences.md, or a Markdown file under a supported namespace"
    }
}

internal fun isArchiveMemoryPath(path: String): Boolean =
    normalizeMemoryPath(path).startsWith("/archive/")

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
    if (!isArchiveMemoryPath(path)) requireStatedOnlyBody(content)
    val allDocumentText = listOf(name, description, aliases.joinToString("\n"), content).joinToString("\n")
    requireSafeMemoryText(allDocumentText)
}

internal fun requireSafeMemoryText(text: String) {
    if (
        secretAssignmentPattern.containsMatchIn(text) ||
        standaloneCredentialPattern.containsMatchIn(text) ||
        cardNumberPattern.containsMatchIn(text) ||
        identityNumberPattern.containsMatchIn(text) ||
        exactPersonalFinancialPattern.containsMatchIn(text)
    ) {
        throw MemoryDocumentPolicyException(
            code = "MEMORY_SENSITIVE_REJECTED",
            message = "Credentials, identity/card numbers, and exact personal financial values cannot be saved",
        )
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

internal fun requireMemorySources(
    sources: List<MemoryDocumentSource>,
    allowDirectUserEdit: Boolean,
    allowMigrationSource: Boolean = false,
) {
    require(sources.isNotEmpty()) { "Memory writes require at least one source" }
    if (sources.size > MEMORY_DOCUMENT_SOURCE_LIMIT) {
        throw MemoryDocumentPolicyException(
            code = "MEMORY_SOURCE_LIMIT_EXCEEDED",
            message = "Memory documents support at most $MEMORY_DOCUMENT_SOURCE_LIMIT distinct sources",
        )
    }
    sources.forEach { source ->
        when (source.type) {
            MemoryDocumentSourceType.CHAT -> require(
                source.conversationId.isNotBlank() &&
                    source.conversationId.length <= 128 &&
                    source.messageId.isNotBlank() &&
                    source.messageId.length <= 128 &&
                    source.quote.trim().length in 2..MEMORY_DOCUMENT_SOURCE_QUOTE_LIMIT &&
                    source.sourceRef.isBlank()
            ) { "Chat memory sources require conversation, message, and exact quote" }

            MemoryDocumentSourceType.USER_EDIT -> require(allowDirectUserEdit) {
                "Only the user-facing editor can create USER_EDIT sources"
            }

            MemoryDocumentSourceType.MIGRATION -> require(allowMigrationSource) {
                "Migration sources are retained only while maintaining an existing archive"
            }
        }
        if (source.quote.isNotBlank()) requireSafeMemoryText(source.quote)
    }
}
