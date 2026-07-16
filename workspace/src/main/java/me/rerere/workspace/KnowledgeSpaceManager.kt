package me.rerere.workspace

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.nio.file.Path

data class KnowledgeSpaceStatus(
    val initialized: Boolean,
    val sourceCount: Int,
    val indexedDocumentCount: Int,
)

data class KnowledgeImportResult(
    val sourcePath: String,
    val normalizedPath: String?,
    val indexed: Boolean,
)

data class KnowledgeSearchMatch(
    val path: String,
    val sourcePath: String,
    val line: Int,
    val excerpt: String,
    val citation: String,
)

data class KnowledgeSearchResult(
    val query: String,
    val matches: List<KnowledgeSearchMatch>,
    val truncated: Boolean,
)

data class KnowledgeReadResult(
    val path: String,
    val sourcePath: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val citation: String,
)

/**
 * File-backed project knowledge space built on top of [WorkspaceManager].
 *
 * All operations are local and work without a Rootfs. Original imports are the source of truth;
 * normalized Markdown and metadata under `.zhixing` are rebuildable derivatives.
 */
class KnowledgeSpaceManager(
    private val workspaceManager: WorkspaceManager,
) {
    fun initialize(root: String, displayName: String): KnowledgeSpaceStatus {
        workspaceManager.ensureWorkspace(root)
        DIRECTORIES.forEach { workspaceManager.createDirectory(root, it) }

        if (!workspaceManager.exists(root, PROJECT_FILE)) {
            workspaceManager.writeText(
                root = root,
                path = PROJECT_FILE,
                text = projectTemplate(displayName.trim().ifBlank { "未命名项目" }),
                overwrite = false,
            )
        }
        if (!workspaceManager.exists(root, MARKER_FILE)) {
            workspaceManager.writeText(
                root = root,
                path = MARKER_FILE,
                text = buildJsonObject {
                    put("formatVersion", 1)
                    put("type", "project")
                }.toString(),
                overwrite = false,
            )
        }
        return status(root)
    }

    fun status(root: String): KnowledgeSpaceStatus {
        workspaceManager.ensureWorkspace(root)
        val initialized = workspaceManager.exists(root, MARKER_FILE)
        return KnowledgeSpaceStatus(
            initialized = initialized,
            sourceCount = countFiles(root, SOURCES_DIR),
            indexedDocumentCount = countFiles(root, NORMALIZED_DIR),
        )
    }

    fun importSource(
        root: String,
        fileName: String,
        inputStream: InputStream,
        normalizedText: String?,
    ): KnowledgeImportResult {
        require(status(root).initialized) { "Knowledge space is not initialized" }
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        require(safeName.isNotBlank() && safeName != "." && safeName != "..") { "Invalid file name" }

        val source = workspaceManager.importFile(
            root = root,
            destinationPath = SOURCES_DIR,
            fileName = safeName,
            inputStream = inputStream,
        )
        val normalizedPath = normalizedText
            ?.takeIf { it.isNotBlank() }
            ?.let { text ->
                val path = "$NORMALIZED_DIR/${source.name}.md"
                workspaceManager.writeText(
                    root = root,
                    path = path,
                    text = "<!-- zhixing-source: ${source.path} -->\n\n${text.trim()}\n",
                    overwrite = false,
                )
                path
            }

        val metadataName = source.name.replace(Regex("[^A-Za-z0-9._()\\-\\u4e00-\\u9fff]"), "_")
        workspaceManager.writeText(
            root = root,
            path = "$METADATA_DIR/$metadataName.json",
            text = buildJsonObject {
                put("formatVersion", 1)
                put("sourcePath", source.path)
                normalizedPath?.let { put("normalizedPath", it) }
                put("indexed", normalizedPath != null)
            }.toString(),
            overwrite = false,
        )
        return KnowledgeImportResult(
            sourcePath = source.path,
            normalizedPath = normalizedPath,
            indexed = normalizedPath != null,
        )
    }

    fun search(root: String, query: String, limit: Int = DEFAULT_SEARCH_LIMIT): KnowledgeSearchResult {
        require(query.isNotBlank()) { "Search query is required" }
        require(status(root).initialized) { "Knowledge space is not initialized" }
        val safeLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val matches = mutableListOf<KnowledgeSearchMatch>()

        for (path in SEARCH_PATHS) {
            if (!workspaceManager.exists(root, path)) continue
            val pathMatches = workspaceManager.grep(
                root = root,
                query = query,
                path = path,
                regex = false,
                ignoreCase = true,
            )
            for (match in pathMatches) {
                if (matches.size > safeLimit) break
                matches += KnowledgeSearchMatch(
                    path = match.path,
                    sourcePath = sourcePath(root, match.path),
                    line = match.line,
                    excerpt = match.text.trim().take(MAX_EXCERPT_CHARS),
                    citation = "workspace://${match.path}#L${match.line}",
                )
            }
            if (matches.size > safeLimit) break
        }

        return KnowledgeSearchResult(
            query = query,
            matches = matches.take(safeLimit),
            truncated = matches.size > safeLimit,
        )
    }

    fun read(
        root: String,
        path: String,
        startLine: Int = 1,
        endLine: Int? = null,
    ): KnowledgeReadResult {
        val normalizedPath = normalizeKnowledgePath(path)
        require(isReadableKnowledgePath(normalizedPath)) { "Path is outside the readable knowledge boundary: $path" }
        require(startLine >= 1) { "startLine must be at least 1" }
        val lines = workspaceManager.readText(root, normalizedPath).lines()
        require(startLine <= lines.size) { "startLine exceeds file length" }
        val resolvedEnd = (endLine ?: (startLine + DEFAULT_READ_LINES - 1))
            .coerceAtMost(lines.size)
        require(resolvedEnd >= startLine) { "endLine must not be before startLine" }
        require(resolvedEnd - startLine + 1 <= MAX_READ_LINES) { "Requested line range is too large" }
        val citation = if (resolvedEnd == startLine) {
            "workspace://$normalizedPath#L$startLine"
        } else {
            "workspace://$normalizedPath#L$startLine-L$resolvedEnd"
        }
        return KnowledgeReadResult(
            path = normalizedPath,
            sourcePath = sourcePath(root, normalizedPath),
            startLine = startLine,
            endLine = resolvedEnd,
            text = lines.subList(startLine - 1, resolvedEnd).joinToString("\n"),
            citation = citation,
        )
    }

    private fun sourcePath(root: String, path: String): String {
        if (!path.startsWith("$NORMALIZED_DIR/")) return path
        val firstLine = workspaceManager.readText(root, path).lineSequence().firstOrNull().orEmpty()
        return SOURCE_MARKER.matchEntire(firstLine)?.groupValues?.get(1) ?: path
    }

    private fun countFiles(root: String, path: String): Int {
        if (!workspaceManager.exists(root, path)) return 0
        return workspaceManager.glob(root, "$path/**")
            .count { !it.isDirectory }
    }

    private fun isReadableKnowledgePath(path: String): Boolean =
        path == PROJECT_FILE || READABLE_PREFIXES.any { path.startsWith(it) }

    private fun normalizeKnowledgePath(path: String): String {
        val candidate = path.replace('\\', '/').trim().trimStart('/')
        require(candidate.isNotBlank() && !candidate.contains('\u0000')) { "Invalid knowledge path: $path" }
        val normalized = Path.of(candidate).normalize().joinToString("/")
        require(normalized == candidate && normalized != "." && !normalized.startsWith("../")) {
            "Knowledge path must already be normalized: $path"
        }
        return normalized
    }

    private fun projectTemplate(name: String): String = """
        # $name

        ## 项目目标

        在这里记录项目要解决的问题和验收标准。

        ## 当前约束

        - 本空间中的项目事实应优先引用 `knowledge/` 下的资料。
        - 重要结论写入 `knowledge/decisions/`，过程笔记写入 `knowledge/notes/`。

        ## 工作方式

        先检索和阅读来源，再形成结论；没有资料支持时明确标注假设。
    """.trimIndent() + "\n"

    companion object {
        const val PROJECT_FILE = "PROJECT.md"
        const val SOURCES_DIR = "knowledge/sources"
        const val NORMALIZED_DIR = ".zhixing/knowledge/normalized"
        const val METADATA_DIR = ".zhixing/knowledge/metadata"
        const val MARKER_FILE = ".zhixing/knowledge-space.json"

        private const val DEFAULT_SEARCH_LIMIT = 20
        private const val MAX_SEARCH_LIMIT = 50
        private const val DEFAULT_READ_LINES = 120
        private const val MAX_READ_LINES = 200
        private const val MAX_EXCERPT_CHARS = 500
        private val SOURCE_MARKER = Regex("<!-- zhixing-source: (.+) -->")
        private val DIRECTORIES = listOf(
            SOURCES_DIR,
            "knowledge/notes",
            "knowledge/decisions",
            "knowledge/outputs",
            "knowledge/drafts",
            NORMALIZED_DIR,
            METADATA_DIR,
        )
        private val SEARCH_PATHS = listOf(
            PROJECT_FILE,
            "knowledge/notes",
            "knowledge/decisions",
            "knowledge/outputs",
            "knowledge/drafts",
            NORMALIZED_DIR,
        )
        private val READABLE_PREFIXES = listOf(
            "knowledge/notes/",
            "knowledge/decisions/",
            "knowledge/outputs/",
            "knowledge/drafts/",
            "$NORMALIZED_DIR/",
        )
    }
}
