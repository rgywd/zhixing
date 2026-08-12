package me.rerere.workspace

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.nio.file.Paths
import java.security.MessageDigest

data class KnowledgeSpaceStatus(
    val initialized: Boolean,
    val contentRoot: String,
    val contentFileCount: Int,
    val indexedDocumentCount: Int,
)

data class AssistantUserPromptDocument(
    val path: String,
    val content: String,
    val revision: String,
)

class AssistantUserPromptConflictException(
    val current: AssistantUserPromptDocument?,
) : IllegalStateException("Assistant user prompt changed since it was read")

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
 * File-backed OrbitOS CN vault built on top of [WorkspaceManager].
 *
 * User-authored content lives under `/workspace/vault` and can be maintained by Git.
 * Normalized text and metadata under the workspace-level `.zhixing` directory are local,
 * rebuildable derivatives and never become the only source of truth.
 */
class KnowledgeSpaceManager(
    private val workspaceManager: WorkspaceManager,
) {
    fun initialize(root: String, displayName: String): KnowledgeSpaceStatus {
        workspaceManager.ensureWorkspace(root)
        DIRECTORIES.forEach { workspaceManager.createDirectory(root, it) }

        writeIfMissing(root, AGENTS_FILE, agentsTemplate(displayName.trim().ifBlank { "个人知识库" }))
        writeIfMissing(root, "$VAULT_DIR/CLAUDE.md", bridgeTemplate("Claude Code"))
        writeIfMissing(root, "$VAULT_DIR/GEMINI.md", bridgeTemplate("Gemini CLI"))
        writeIfMissing(root, "$VAULT_DIR/.gitignore", environmentGitIgnore())
        writeIfMissing(root, "$TOOLS_DIR/README.md", toolsReadme())
        TEMPLATE_FILES.forEach { (path, text) -> writeIfMissing(root, path, text) }
        GIT_KEEP_FILES.forEach { path -> writeIfMissing(root, path, "") }

        if (!workspaceManager.exists(root, MARKER_FILE)) {
            workspaceManager.writeText(
                root = root,
                path = MARKER_FILE,
                text = buildJsonObject {
                    put("formatVersion", 2)
                    put("type", "orbitos-cn-vault")
                    put("contentRoot", VAULT_DIR)
                }.toString(),
                overwrite = false,
            )
        }
        return status(root)
    }

    /**
     * An existing phone vault is adopted in place without requiring an app-local marker.
     * `vault/AGENTS.md` is the stable, Git-synced identity and behavior contract.
     */
    fun isInitialized(root: String): Boolean =
        workspaceManager.exists(root, VAULT_DIR) &&
            workspaceManager.exists(root, AGENTS_FILE)

    fun status(root: String): KnowledgeSpaceStatus {
        workspaceManager.ensureWorkspace(root)
        val initialized = isInitialized(root)
        return KnowledgeSpaceStatus(
            initialized = initialized,
            contentRoot = VAULT_DIR,
            contentFileCount = if (initialized) countVaultContentFiles(root) else 0,
            indexedDocumentCount = if (initialized) countSearchableDocuments(root) else 0,
        )
    }

    fun readAssistantUserPrompt(root: String, assistantId: String): AssistantUserPromptDocument? {
        require(isInitialized(root)) { "Knowledge vault is not initialized" }
        val path = assistantUserPromptPath(assistantId)
        if (!workspaceManager.exists(root, path)) return null
        val size = workspaceManager.fileSize(root, path)
        require(size <= MAX_USER_PROMPT_BYTES) {
            "Assistant user prompt is too large: $size bytes"
        }
        val content = workspaceManager.readText(root, path)
        return AssistantUserPromptDocument(
            path = path,
            content = content,
            revision = content.sha256(),
        )
    }

    @Synchronized
    fun ensureAssistantUserPrompt(
        root: String,
        assistantId: String,
        fallbackContent: String,
    ): AssistantUserPromptDocument {
        readAssistantUserPrompt(root, assistantId)?.let { return it }
        return writeAssistantUserPrompt(
            root = root,
            assistantId = assistantId,
            content = fallbackContent,
            expectedRevision = null,
        )
    }

    @Synchronized
    fun writeAssistantUserPrompt(
        root: String,
        assistantId: String,
        content: String,
        expectedRevision: String?,
    ): AssistantUserPromptDocument {
        require(isInitialized(root)) { "Knowledge vault is not initialized" }
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        require(contentBytes.size <= MAX_USER_PROMPT_BYTES) {
            "Assistant user prompt is too large: ${contentBytes.size} bytes"
        }
        val current = readAssistantUserPrompt(root, assistantId)
        if (current?.revision != expectedRevision) {
            throw AssistantUserPromptConflictException(current)
        }
        val path = assistantUserPromptPath(assistantId)
        workspaceManager.writeTextAtomically(root, path, content)
        return AssistantUserPromptDocument(
            path = path,
            content = content,
            revision = content.sha256(),
        )
    }

    /**
     * Lists user-visible vault content without exposing Git internals or local environment files.
     *
     * [path] is relative to `vault/`; returned entry paths remain workspace-relative so the
     * existing preview/editor route can open them directly.
     */
    fun listContents(root: String, path: String = ""): List<WorkspaceFileEntry> {
        require(isInitialized(root)) { "Knowledge vault is not initialized" }
        val relativePath = normalizeVaultRelativePath(path)
        val workspacePath = if (relativePath.isBlank()) {
            VAULT_DIR
        } else {
            "$VAULT_DIR/$relativePath"
        }
        return workspaceManager.listFiles(root, workspacePath)
            .filterNot { it.name.startsWith(".") }
    }

    fun importSource(
        root: String,
        fileName: String,
        inputStream: InputStream,
        normalizedText: String?,
    ): KnowledgeImportResult {
        require(isInitialized(root)) { "Knowledge vault is not initialized" }
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        require(safeName.isNotBlank() && safeName != "." && safeName != "..") { "Invalid file name" }

        val source = workspaceManager.importFile(
            root = root,
            destinationPath = INBOX_DIR,
            fileName = safeName,
            inputStream = inputStream,
        )
        val directlySearchable = isSearchableTextPath(source.path)
        val normalizedPath = normalizedText
            ?.takeIf { it.isNotBlank() && !directlySearchable }
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
        val indexed = directlySearchable || normalizedPath != null

        val metadataName = source.name.replace(Regex("[^A-Za-z0-9._()\\-\\u4e00-\\u9fff]"), "_")
        workspaceManager.writeText(
            root = root,
            path = "$METADATA_DIR/$metadataName.json",
            text = buildJsonObject {
                put("formatVersion", 2)
                put("sourcePath", source.path)
                normalizedPath?.let { put("normalizedPath", it) }
                put("indexed", indexed)
            }.toString(),
            overwrite = false,
        )
        return KnowledgeImportResult(
            sourcePath = source.path,
            normalizedPath = normalizedPath,
            indexed = indexed,
        )
    }

    fun search(root: String, query: String, limit: Int = DEFAULT_SEARCH_LIMIT): KnowledgeSearchResult {
        require(query.isNotBlank()) { "Search query is required" }
        require(isInitialized(root)) { "Knowledge vault is not initialized" }
        val safeLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val matches = mutableListOf<KnowledgeSearchMatch>()

        ROOT_GUIDANCE_FILES.forEach { path ->
            if (!workspaceManager.exists(root, path) || matches.size > safeLimit) return@forEach
            matches += searchPath(root, query, path, includeGlob = null)
        }
        SEARCH_DIRECTORIES.forEach { path ->
            if (!workspaceManager.exists(root, path) || matches.size > safeLimit) return@forEach
            matches += searchPath(root, query, path, includeGlob = SEARCHABLE_TEXT_GLOB)
        }
        if (workspaceManager.exists(root, NORMALIZED_DIR) && matches.size <= safeLimit) {
            matches += searchPath(root, query, NORMALIZED_DIR, includeGlob = "**/*.md")
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
        require(isReadableKnowledgePath(normalizedPath)) {
            "Path is outside the readable vault boundary: $path"
        }
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

    private fun searchPath(
        root: String,
        query: String,
        path: String,
        includeGlob: String?,
    ): List<KnowledgeSearchMatch> =
        workspaceManager.grep(
            root = root,
            query = query,
            path = path,
            regex = false,
            ignoreCase = true,
            includeGlob = includeGlob,
        ).filterNot { match ->
            isSecretEnvironmentPath(match.path)
        }.map { match ->
            KnowledgeSearchMatch(
                path = match.path,
                sourcePath = sourcePath(root, match.path),
                line = match.line,
                excerpt = match.text.trim().take(MAX_EXCERPT_CHARS),
                citation = "workspace://${match.path}#L${match.line}",
            )
        }

    private fun sourcePath(root: String, path: String): String {
        if (!path.startsWith("$NORMALIZED_DIR/")) return path
        val firstLine = workspaceManager.readText(root, path).lineSequence().firstOrNull().orEmpty()
        return SOURCE_MARKER.matchEntire(firstLine)?.groupValues?.get(1) ?: path
    }

    private fun countVaultContentFiles(root: String): Int =
        CONTENT_DIRECTORIES.sumOf { path ->
            countFiles(root, path, excludeNames = setOf(".gitkeep"))
        } +
            ROOT_GUIDANCE_FILES.count { workspaceManager.exists(root, it) }

    private fun countSearchableDocuments(root: String): Int =
        SEARCH_DIRECTORIES.sumOf { path ->
            countFiles(root, path, includeExtensions = SEARCHABLE_TEXT_EXTENSIONS)
        } +
            ROOT_GUIDANCE_FILES.count { workspaceManager.exists(root, it) } +
            countFiles(root, NORMALIZED_DIR, includeExtensions = setOf("md"))

    private fun countFiles(
        root: String,
        path: String,
        includeExtensions: Set<String>? = null,
        excludeNames: Set<String> = emptySet(),
    ): Int {
        if (!workspaceManager.exists(root, path)) return 0
        return workspaceManager.countFiles(root, path, includeExtensions, excludeNames)
    }

    private fun isReadableKnowledgePath(path: String): Boolean =
        (path in ROOT_GUIDANCE_FILES ||
            READABLE_PREFIXES.any { path.startsWith(it) }) &&
            isSearchableTextPath(path) &&
            !isSecretEnvironmentPath(path)

    private fun isSearchableTextPath(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in SEARCHABLE_TEXT_EXTENSIONS

    private fun normalizeKnowledgePath(path: String): String {
        val candidate = path.replace('\\', '/').trim().trimStart('/')
        require(candidate.isNotBlank() && !candidate.contains('\u0000')) { "Invalid knowledge path: $path" }
        val normalized = Paths.get(candidate).normalize().joinToString("/")
        require(normalized == candidate && normalized != "." && !normalized.startsWith("../")) {
            "Knowledge path must already be normalized: $path"
        }
        return normalized
    }

    private fun normalizeVaultRelativePath(path: String): String {
        val candidate = path.replace('\\', '/').trim().trim('/')
        if (candidate.isBlank()) return ""
        require(!candidate.contains('\u0000')) { "Invalid vault path: $path" }
        val normalized = Paths.get(candidate).normalize().joinToString("/")
        require(normalized == candidate && normalized != "." && !normalized.startsWith("../")) {
            "Vault path must stay inside the content root: $path"
        }
        return normalized
    }

    private fun isSecretEnvironmentPath(path: String): Boolean =
        path.replace('\\', '/')
            .split('/')
            .any { segment -> segment == ".env" || segment.startsWith(".env.") }

    private fun writeIfMissing(root: String, path: String, text: String) {
        if (!workspaceManager.exists(root, path)) {
            workspaceManager.writeText(root, path, text, overwrite = false)
        }
    }

    private fun agentsTemplate(name: String): String = """
        # $name — OrbitOS CN Vault

        这是本知识库的维护契约。先捕获，再按目录职责归类；已有用户内容不得被覆盖。

        ## 目录与命名

        | 类型 | 目录 | frontmatter `type` | 命名 |
        | --- | --- | --- | --- |
        | 收件箱条目 | `00_收件箱/` | 不要求 | 随意，AI 后续归类 |
        | 日记 | `10_日记/` | 不要求 | `YYYY-MM-DD.md` |
        | 项目 | `20_项目/` | 不要求 | C.A.P.：Context / Actions / Progress |
        | 研究主笔记 | `30_研究/<领域>/<主题>/` | `reference` | `<主题>.md` |
        | 原子概念 | `40_知识库/<分类>/` | 不要求，使用 Wiki 模板 | `<概念名>.md` |
        | 工具条目 | `60_工具/<类别>/` | `tool` | `<工具名>.md` |
        | 计划 | `90_计划/` | 不要求 | `Plan_YYYY-MM-DD_<主题>.md` |

        `50_资源/` 保存精选外部资源；`99_系统/` 保存模板、数据库视图和领域提示词。

        ## AI 工作方式

        - 默认先把未分类内容放入 `00_收件箱/`，不要猜测归属。
        - 研究主笔记和工具条目必须保留对应的 `type`。
        - 项目使用 C.A.P. 结构，不按领域目录拆分。
        - 使用 vault 相对路径与 Obsidian wikilink；不写入凭据，不改动 `.git/`。
        - `.env` 只保存本地变量并由 Git 忽略；AI 只能按变量名调用，不得读取、显示或记录变量值。
        - 工作流技能位于 `.agents/skills/<skill>/SKILL.md`，使用前先读取。
        - 只有用户明确要求时才执行 Git 提交、拉取、推送或冲突处理。
    """.trimIndent() + "\n"

    private fun bridgeTemplate(client: String): String = """
        # $client

        维护本 vault 前先读取 `AGENTS.md`；工作流技能统一位于 `.agents/skills/`。
    """.trimIndent() + "\n"

    private fun toolsReadme(): String = """
        # 工具库

        `60_工具/<类别>/<工具名>.md` 同时承载在用工具的说明和待实现的工具创意。

        每个工具条目使用：

        ```yaml
        ---
        type: tool
        ---
        ```

        工具条目应记录用途、入口、输入输出、限制和来源；脚本可以与条目放在同一类别目录，但不得写入凭据。
    """.trimIndent() + "\n"

    private fun environmentGitIgnore(): String = """
        # Local environment variables
        .env
        .env.*
        !.env.example
    """.trimIndent() + "\n"

    companion object {
        const val VAULT_DIR = "vault"
        const val AGENTS_FILE = "$VAULT_DIR/AGENTS.md"
        const val INBOX_DIR = "$VAULT_DIR/00_收件箱"
        const val TOOLS_DIR = "$VAULT_DIR/60_工具"
        const val NORMALIZED_DIR = ".zhixing/knowledge/normalized"
        const val METADATA_DIR = ".zhixing/knowledge/metadata"
        const val MARKER_FILE = ".zhixing/knowledge-space.json"
        const val USER_PROMPTS_DIR = "$VAULT_DIR/99_系统/提示词/助手"

        private const val DEFAULT_SEARCH_LIMIT = 20
        private const val MAX_SEARCH_LIMIT = 50
        private const val DEFAULT_READ_LINES = 120
        private const val MAX_READ_LINES = 200
        private const val MAX_EXCERPT_CHARS = 500
        private const val MAX_USER_PROMPT_BYTES = 128L * 1024
        private val SOURCE_MARKER = Regex("<!-- zhixing-source: (.+) -->")
        private val CONTENT_DIRECTORIES = listOf(
            "$VAULT_DIR/00_收件箱",
            "$VAULT_DIR/10_日记",
            "$VAULT_DIR/20_项目",
            "$VAULT_DIR/30_研究",
            "$VAULT_DIR/40_知识库",
            "$VAULT_DIR/50_资源",
            "$VAULT_DIR/60_工具",
            "$VAULT_DIR/90_计划",
            "$VAULT_DIR/99_系统",
            "$VAULT_DIR/.agents/skills",
        )
        private val SEARCH_DIRECTORIES = CONTENT_DIRECTORIES
        private val ROOT_GUIDANCE_FILES = listOf(
            AGENTS_FILE,
            "$VAULT_DIR/CLAUDE.md",
            "$VAULT_DIR/GEMINI.md",
        )
        private val READABLE_PREFIXES = SEARCH_DIRECTORIES.map { "$it/" } +
            "$NORMALIZED_DIR/"
        private val DIRECTORIES = CONTENT_DIRECTORIES + listOf(
            "$VAULT_DIR/99_系统/模板",
            "$VAULT_DIR/99_系统/数据库",
            "$VAULT_DIR/99_系统/提示词",
            USER_PROMPTS_DIR,
            "$VAULT_DIR/.claude",
            "$VAULT_DIR/.codex",
            "$VAULT_DIR/.gemini/commands",
            NORMALIZED_DIR,
            METADATA_DIR,
        )
        private val GIT_KEEP_FILES = listOf(
            "$VAULT_DIR/00_收件箱/.gitkeep",
            "$VAULT_DIR/20_项目/.gitkeep",
            "$VAULT_DIR/90_计划/.gitkeep",
        )
        private val SEARCHABLE_TEXT_EXTENSIONS = setOf(
            "md", "markdown", "txt", "csv", "tsv", "json", "jsonl", "xml", "html", "htm",
            "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "css", "scss", "sql", "sh",
            "yaml", "yml", "toml", "ini", "properties", "log", "base",
        )
        private val SEARCHABLE_TEXT_GLOB =
            "**/*.{${SEARCHABLE_TEXT_EXTENSIONS.sorted().joinToString(",")}}"
        private val TEMPLATE_FILES = mapOf(
            "$VAULT_DIR/99_系统/模板/Daily_Note.md" to """
                # {{date:YYYY-MM-DD}}

                ## 待办

                ## 日志

                ## AI 摘要

                ## 相关项目
            """.trimIndent() + "\n",
            "$VAULT_DIR/99_系统/模板/Inbox_Template.md" to """
                # {{title}}

                ## 原始内容

                ## 后续处理
            """.trimIndent() + "\n",
            "$VAULT_DIR/99_系统/模板/Project_Template.md" to """
                # {{title}}

                ## Context

                ## Actions

                ## Progress
            """.trimIndent() + "\n",
            "$VAULT_DIR/99_系统/模板/Wiki_Template.md" to """
                # {{title}}

                ## 定义

                ## 关联
            """.trimIndent() + "\n",
            "$VAULT_DIR/99_系统/模板/Content_Template.md" to """
                ---
                type: reference
                ---
                # {{title}}

                ## 摘要

                ## 来源
            """.trimIndent() + "\n",
        )
    }

    private fun assistantUserPromptPath(assistantId: String): String {
        require(assistantId.matches(Regex("[A-Za-z0-9-]{1,64}"))) {
            "Invalid assistant id"
        }
        return "$USER_PROMPTS_DIR/$assistantId.md"
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
