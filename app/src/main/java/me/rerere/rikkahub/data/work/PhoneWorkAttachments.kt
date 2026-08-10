package me.rerere.rikkahub.data.work

data class PhoneWorkPendingAttachment(
    val uri: String,
    val fileName: String? = null,
    val mimeType: String? = null,
)

private val workAttachmentExtensions = setOf(
    "png", "jpg", "jpeg", "webp", "gif",
    "txt", "md", "markdown", "mdx", "csv", "json", "js", "jsx", "mjs", "cjs",
    "html", "css", "vue", "svelte", "xml", "py", "rb", "lua", "sql", "java", "kt",
    "ts", "tsx", "dart", "php", "swift", "go", "bat", "cmd", "ps1", "psm1", "sh",
    "bash", "zsh", "fish", "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx", "rs",
    "cs", "toml", "ini", "env", "gradle", "kts", "properties", "proto", "graphql", "gql",
    "yml", "yaml", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub",
    "zip", "7z", "gz",
)

private val workAttachmentMimeTypes = setOf(
    "image/png", "image/jpeg", "image/webp", "image/gif",
    "application/json", "application/javascript", "application/xml", "application/yaml", "application/x-yaml",
    "application/toml", "application/pdf", "application/msword", "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/epub+zip",
    "application/zip", "application/x-zip-compressed", "application/x-7z-compressed",
    "application/gzip", "application/x-gzip",
)

fun isAllowedWorkAttachmentType(fileName: String, mimeType: String): Boolean {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension in workAttachmentExtensions) return true
    val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
    return extension.isBlank() && (normalizedMime.startsWith("text/") || normalizedMime in workAttachmentMimeTypes)
}

fun PhoneWorkCatalog.supportsFileAttachments(runnerId: String?): Boolean = runnerId != null && runners
    .firstOrNull { it.id == runnerId }
    ?.capabilities
    ?.fileAttachments
    ?.let { it >= 1 }
    ?: false
