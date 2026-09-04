package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDocumentDAO
import me.rerere.rikkahub.data.db.entity.MemoryDocumentEntity
import me.rerere.rikkahub.data.db.fts.MemoryDocumentSearchHit
import me.rerere.rikkahub.data.db.fts.MemoryDocumentSearchIndex
import me.rerere.rikkahub.data.db.fts.MemoryDocumentSearchVisibility
import me.rerere.rikkahub.data.memory.normalizeMemoryPath
import me.rerere.rikkahub.data.memory.isArchiveMemoryPath
import me.rerere.rikkahub.data.memory.requireMemorySources
import me.rerere.rikkahub.data.memory.requireValidMemoryDocument
import me.rerere.rikkahub.data.memory.requireWritableMemoryPath
import me.rerere.rikkahub.data.model.MemoryDocument
import me.rerere.rikkahub.data.model.MemoryDocumentSource
import me.rerere.rikkahub.data.model.MemoryDocumentSourceType
import me.rerere.rikkahub.data.model.MemoryDocumentState
import me.rerere.rikkahub.utils.JsonInstant
import java.text.Normalizer
import java.util.Locale

class MemoryDocumentConflictException(
    val current: MemoryDocument?,
) : IllegalStateException(
    current?.let { "Memory document version conflict; current version is ${it.version}" }
        ?: "Memory document version conflict; document no longer exists"
)

data class MemoryDocumentDescriptor(
    val path: String,
    val name: String,
    val description: String,
    val aliases: List<String>,
    val version: Long,
)

data class MemoryDocumentFindResult(
    val items: List<MemoryDocumentDescriptor>,
    val truncated: Boolean,
)

data class MemoryDocumentListPage(
    val items: List<MemoryDocumentDescriptor>,
    val total: Int,
    val hasMore: Boolean,
    val nextCursor: String?,
)

class MemoryDocumentRepository(
    private val dao: MemoryDocumentDAO,
    private val searchIndex: MemoryDocumentSearchIndex,
) {
    companion object {
        const val GLOBAL_SCOPE_ID = "__global__"
        const val PROFILE_PATH = "/profile.md"
        const val PREFERENCES_PATH = "/preferences.md"
        val PINNED_PATHS = setOf(PROFILE_PATH, PREFERENCES_PATH)
        val RECALL_PREFIXES = setOf("/", "/areas", "/topics", "/people", "/archive")
    }

    fun observeDocuments(contextScopeId: String): Flow<List<MemoryDocument>> =
        dao.observeActive(scopeIds(contextScopeId)).map { preferScopedDocuments(it, contextScopeId) }

    suspend fun listDocuments(contextScopeId: String): List<MemoryDocument> {
        ensurePinnedDocuments()
        return preferScopedDocuments(dao.listActive(scopeIds(contextScopeId)), contextScopeId)
    }

    suspend fun getPromptDocuments(contextScopeId: String): List<MemoryDocument> {
        ensurePinnedDocuments()
        return PINNED_PATHS.sorted()
            .mapNotNull { path -> findVisible(contextScopeId, path)?.toMemoryDocument() }
    }

    suspend fun findDocuments(
        contextScopeId: String,
        rawQuery: String,
        rawPrefix: String? = null,
        limit: Int = 5,
    ): MemoryDocumentFindResult {
        ensurePinnedDocuments()
        val query = rawQuery.trim()
        require(query.length in 1..200) { "Memory query must contain 1-200 characters" }
        require(limit in 1..10) { "Memory find limit must be between 1 and 10" }
        val prefix = normalizeRecallPrefix(rawPrefix)
        val visibility = MemoryDocumentSearchVisibility(
            contextScopeId = contextScopeId,
            globalScopeId = GLOBAL_SCOPE_ID,
            globalPinnedPaths = PINNED_PATHS.sorted(),
        )
        val hits = searchIndex.search(
            query = query,
            prefix = prefix,
            limit = limit + 1,
            visibility = visibility,
        ).filter { hit ->
            isVisibleSearchHit(hit, contextScopeId) &&
                (prefix == "/" || hit.path.startsWith("$prefix/"))
        }
        return MemoryDocumentFindResult(
            items = hits.take(limit).map(MemoryDocumentSearchHit::toDescriptor),
            truncated = hits.size > limit,
        )
    }

    suspend fun listDocumentDescriptors(
        contextScopeId: String,
        rawPrefix: String? = null,
        rawCursor: String? = null,
        limit: Int = 10,
    ): MemoryDocumentListPage {
        require(limit in 1..20) { "Memory list limit must be between 1 and 20" }
        val prefix = normalizeRecallPrefix(rawPrefix)
        val cursor = rawCursor?.let(::normalizeMemoryPath)?.also { normalized ->
            require(prefix == "/" || normalized.startsWith("$prefix/")) {
                "Memory list cursor must belong to the requested prefix"
            }
        }
        val matching = listDocuments(contextScopeId)
            .asSequence()
            .filter { prefix == "/" || it.path.startsWith("$prefix/") }
            .sortedBy(MemoryDocument::path)
            .toList()
        val remaining = matching.filter { cursor == null || it.path > cursor }
        val pageItems = remaining.take(limit)
        val hasMore = remaining.size > pageItems.size
        return MemoryDocumentListPage(
            items = pageItems.map(MemoryDocument::toDescriptor),
            total = matching.size,
            hasMore = hasMore,
            nextCursor = pageItems.lastOrNull()?.path?.takeIf { hasMore },
        )
    }

    suspend fun read(contextScopeId: String, rawPath: String): MemoryDocument {
        ensurePinnedDocuments()
        val path = normalizeMemoryPath(rawPath)
        val entity = findVisible(contextScopeId, path)
            ?: error("Memory document $path not found")
        return entity.toMemoryDocument()
    }

    suspend fun writeFromChat(
        contextScopeId: String,
        rawPath: String,
        expectedVersion: Long,
        name: String,
        description: String,
        aliases: List<String>,
        content: String,
        sources: List<MemoryDocumentSource>,
    ): MemoryDocument = write(
        contextScopeId = contextScopeId,
        rawPath = rawPath,
        expectedVersion = expectedVersion,
        name = name,
        description = description,
        aliases = aliases,
        content = content,
        sources = sources,
        allowDirectUserEdit = false,
    )

    suspend fun writeFromUserEditor(
        contextScopeId: String,
        rawPath: String,
        expectedVersion: Long,
        name: String,
        description: String,
        aliases: List<String>,
        content: String,
    ): MemoryDocument {
        val existingSources = if (expectedVersion > 0) {
            runCatching { read(contextScopeId, rawPath).sources }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return write(
            contextScopeId = contextScopeId,
            rawPath = rawPath,
            expectedVersion = expectedVersion,
            name = name,
            description = description,
            aliases = aliases,
            content = content,
            sources = existingSources + MemoryDocumentSource(
                type = MemoryDocumentSourceType.USER_EDIT,
                observedAt = System.currentTimeMillis(),
            ),
            allowDirectUserEdit = true,
        )
    }

    suspend fun appendFromChat(
        contextScopeId: String,
        rawPath: String,
        expectedVersion: Long,
        content: String,
        sources: List<MemoryDocumentSource>,
    ): MemoryDocument {
        val current = read(contextScopeId, rawPath)
        if (current.version != expectedVersion) throw MemoryDocumentConflictException(current)
        val next = listOf(current.content.trimEnd(), content.trim()).filter(String::isNotBlank).joinToString("\n")
        return write(
            contextScopeId = contextScopeId,
            rawPath = current.path,
            expectedVersion = expectedVersion,
            name = current.name,
            description = current.description,
            aliases = current.aliases,
            content = next,
            sources = current.sources + sources,
            allowDirectUserEdit = false,
        )
    }

    suspend fun replaceFromChat(
        contextScopeId: String,
        rawPath: String,
        expectedVersion: Long,
        oldText: String,
        newText: String,
        sources: List<MemoryDocumentSource>,
    ): MemoryDocument {
        require(oldText.isNotEmpty()) { "oldText must not be empty" }
        val current = read(contextScopeId, rawPath)
        if (current.version != expectedVersion) throw MemoryDocumentConflictException(current)
        val first = current.content.indexOf(oldText)
        require(first >= 0) { "oldText was not found in memory document" }
        require(current.content.indexOf(oldText, first + oldText.length) < 0) {
            "oldText must match exactly one location"
        }
        return write(
            contextScopeId = contextScopeId,
            rawPath = current.path,
            expectedVersion = expectedVersion,
            name = current.name,
            description = current.description,
            aliases = current.aliases,
            content = current.content.replaceRange(first, first + oldText.length, newText),
            sources = current.sources + sources,
            allowDirectUserEdit = false,
        )
    }

    suspend fun delete(contextScopeId: String, rawPath: String, expectedVersion: Long) {
        val path = requireWritableMemoryPath(rawPath)
        require(path !in PINNED_PATHS) { "Pinned memory documents cannot be deleted; clear their content instead" }
        val current = findVisible(contextScopeId, path)?.toMemoryDocument()
            ?: throw MemoryDocumentConflictException(null)
        if (current.version != expectedVersion) throw MemoryDocumentConflictException(current)
        val changed = if (isArchiveMemoryPath(path)) {
            ProfileMemoryMutationGate.run {
                dao.compareAndDeleteArchive(
                    scopeId = current.scopeId,
                    path = path,
                    expectedVersion = expectedVersion,
                    updatedAt = System.currentTimeMillis(),
                    legacyIds = current.content.legacyMemoryIds().sorted(),
                )
            }
        } else {
            dao.compareAndDelete(
                scopeId = current.scopeId,
                path = path,
                expectedVersion = expectedVersion,
                updatedAt = System.currentTimeMillis(),
            )
        }
        if (changed != 1) throw MemoryDocumentConflictException(findVisible(contextScopeId, path)?.toMemoryDocument())
    }

    suspend fun deleteScope(scopeId: String) = dao.deleteScope(scopeId)

    /**
     * Removes provenance owned by deleted raw history without deleting the independently curated note.
     * The document version advances so concurrent editors cannot unknowingly restore the removed source.
     */
    suspend fun revokeChatSources(conversationId: String, messageId: String? = null) {
        dao.listWithSources().forEach { snapshot ->
            var current = snapshot
            repeat(3) {
                val sources = current.decodeSources()
                val retained = sources.filterNot { source ->
                    source.type == MemoryDocumentSourceType.CHAT &&
                        source.conversationId == conversationId &&
                        (messageId == null || source.messageId == messageId)
                }
                if (retained.size == sources.size) return@forEach
                val changed = dao.compareAndSetSources(
                    scopeId = current.scopeId,
                    path = current.path,
                    expectedVersion = current.version,
                    sourcesJson = JsonInstant.encodeToString(retained),
                    updatedAt = System.currentTimeMillis(),
                )
                if (changed == 1) return@forEach
                current = dao.find(current.scopeId, current.path) ?: return@forEach
            }
        }
    }

    private suspend fun write(
        contextScopeId: String,
        rawPath: String,
        expectedVersion: Long,
        name: String,
        description: String,
        aliases: List<String>,
        content: String,
        sources: List<MemoryDocumentSource>,
        allowDirectUserEdit: Boolean,
    ): MemoryDocument {
        val path = requireWritableMemoryPath(rawPath)
        require(expectedVersion >= 0) { "if_version must be zero for create or the current positive version" }
        val cleanName = normalizeMemoryMetadata(name)
        val cleanDescription = normalizeMemoryMetadata(description)
        val cleanAliases = aliases
            .map(::normalizeMemoryMetadata)
            .distinctBy { it.lowercase(Locale.ROOT) }
        val cleanContent = normalizeMemoryContent(content)
        requireValidMemoryDocument(path, cleanName, cleanDescription, cleanAliases, cleanContent)
        ensurePinnedDocuments()
        val targetScope = if (path in PINNED_PATHS) GLOBAL_SCOPE_ID else contextScopeId
        val current = dao.find(targetScope, path)
        val archive = isArchiveMemoryPath(path)
        require(!archive || current?.state == MemoryDocumentState.ACTIVE.name) {
            "Archive paths can be maintained only when they already exist"
        }
        val currentLegacyIds = current?.content.orEmpty().legacyMemoryIds()
        val nextLegacyIds = cleanContent.legacyMemoryIds()
        require(!archive || nextLegacyIds.all(currentLegacyIds::contains)) {
            "Existing legacy IDs may be retained or removed, but new legacy IDs cannot be introduced"
        }
        val cleanSources = buildList {
            if (archive) {
                addAll(current.orEmptyMigrationSources())
            }
            addAll(sources)
        }.distinctBy { listOf(it.type.name, it.conversationId, it.messageId, it.quote) }
        requireMemorySources(
            sources = cleanSources,
            allowDirectUserEdit = allowDirectUserEdit,
            allowMigrationSource = archive,
        )
        val now = System.currentTimeMillis()
        if (current == null) {
            if (expectedVersion != 0L) throw MemoryDocumentConflictException(null)
            val inserted = MemoryDocumentEntity(
                scopeId = targetScope,
                path = path,
                name = cleanName,
                description = cleanDescription,
                aliasesJson = JsonInstant.encodeToString(cleanAliases),
                content = cleanContent,
                sourcesJson = JsonInstant.encodeToString(cleanSources),
                version = 1,
                state = MemoryDocumentState.ACTIVE.name,
                createdAt = now,
                updatedAt = now,
            )
            if (dao.insertIgnore(inserted) == -1L) {
                throw MemoryDocumentConflictException(dao.find(targetScope, path)?.toMemoryDocument())
            }
            return inserted.toMemoryDocument()
        }
        if (current.state == MemoryDocumentState.DELETED.name) {
            if (expectedVersion != 0L) throw MemoryDocumentConflictException(current.toMemoryDocument())
            val changed = dao.reactivateDeleted(
                scopeId = targetScope,
                path = path,
                name = cleanName,
                description = cleanDescription,
                aliasesJson = JsonInstant.encodeToString(cleanAliases),
                content = cleanContent,
                sourcesJson = JsonInstant.encodeToString(cleanSources),
                updatedAt = now,
            )
            if (changed != 1) throw MemoryDocumentConflictException(dao.find(targetScope, path)?.toMemoryDocument())
            return dao.find(targetScope, path)?.toMemoryDocument()
                ?: throw MemoryDocumentConflictException(null)
        }
        if (current.version != expectedVersion) throw MemoryDocumentConflictException(current.toMemoryDocument())
        val changed = if (archive) {
            ProfileMemoryMutationGate.run {
                dao.compareAndSetArchive(
                    scopeId = targetScope,
                    path = path,
                    expectedVersion = expectedVersion,
                    name = cleanName,
                    description = cleanDescription,
                    aliasesJson = JsonInstant.encodeToString(cleanAliases),
                    content = cleanContent,
                    sourcesJson = JsonInstant.encodeToString(cleanSources),
                    updatedAt = now,
                    removedLegacyIds = (currentLegacyIds - nextLegacyIds).sorted(),
                )
            }
        } else {
            dao.compareAndSet(
                scopeId = targetScope,
                path = path,
                expectedVersion = expectedVersion,
                name = cleanName,
                description = cleanDescription,
                aliasesJson = JsonInstant.encodeToString(cleanAliases),
                content = cleanContent,
                sourcesJson = JsonInstant.encodeToString(cleanSources),
                updatedAt = now,
            )
        }
        if (changed != 1) throw MemoryDocumentConflictException(dao.find(targetScope, path)?.toMemoryDocument())
        return dao.find(targetScope, path)?.toMemoryDocument()
            ?: throw MemoryDocumentConflictException(null)
    }

    private suspend fun ensurePinnedDocuments() {
        val defaults = listOf(
            MemoryDocumentEntity(
                scopeId = GLOBAL_SCOPE_ID,
                path = PROFILE_PATH,
                name = "Profile",
                description = "Stable user identity and background stated by the user.",
            ),
            MemoryDocumentEntity(
                scopeId = GLOBAL_SCOPE_ID,
                path = PREFERENCES_PATH,
                name = "Preferences",
                description = "Stable response and collaboration preferences stated by the user.",
            ),
        )
        defaults.forEach { dao.insertIgnore(it) }
    }

    private suspend fun findVisible(contextScopeId: String, path: String): MemoryDocumentEntity? {
        val targetScope = if (path in PINNED_PATHS) GLOBAL_SCOPE_ID else contextScopeId
        return dao.find(targetScope, path)?.takeIf { it.state == MemoryDocumentState.ACTIVE.name }
    }

    private fun scopeIds(contextScopeId: String): List<String> =
        listOf(contextScopeId, GLOBAL_SCOPE_ID).distinct()

    private fun preferScopedDocuments(
        entities: List<MemoryDocumentEntity>,
        contextScopeId: String,
    ): List<MemoryDocument> = entities
        .filter { entity ->
            contextScopeId == GLOBAL_SCOPE_ID ||
                entity.scopeId == contextScopeId ||
                entity.path in PINNED_PATHS
        }
        .map(MemoryDocumentEntity::toMemoryDocument)
        .sortedBy(MemoryDocument::path)

    private fun normalizeRecallPrefix(rawPrefix: String?): String {
        val prefix = rawPrefix?.let(::normalizeMemoryPath) ?: "/"
        require(prefix in RECALL_PREFIXES) {
            "Memory prefix must be one of ${RECALL_PREFIXES.sorted().joinToString()}"
        }
        return prefix
    }

    private fun isVisibleSearchHit(hit: MemoryDocumentSearchHit, contextScopeId: String): Boolean =
        if (contextScopeId == GLOBAL_SCOPE_ID) {
            hit.scopeId == GLOBAL_SCOPE_ID
        } else {
            hit.scopeId == contextScopeId ||
                (hit.scopeId == GLOBAL_SCOPE_ID && hit.path in PINNED_PATHS)
        }
}

private fun normalizeMemoryMetadata(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFC)

private fun normalizeMemoryContent(value: String): String = value
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .lineSequence()
    .joinToString("\n", transform = String::trimEnd)
    .trim()

private val legacyMemoryLinePattern = Regex("(?m)^\\s*-\\s*\\[legacy\\]\\s+#(\\d+)\\b")

private fun String.legacyMemoryIds(): Set<Int> = legacyMemoryLinePattern
    .findAll(this)
    .mapNotNull { match -> match.groupValues[1].toIntOrNull() }
    .toSet()

private fun MemoryDocumentEntity?.orEmptyMigrationSources(): List<MemoryDocumentSource> = this
    ?.decodeSources()
    ?.filter { it.type == MemoryDocumentSourceType.MIGRATION }
    .orEmpty()

internal fun MemoryDocumentEntity.toMemoryDocument(): MemoryDocument = MemoryDocument(
    scopeId = scopeId,
    path = path,
    name = name,
    description = description,
    aliases = runCatching { JsonInstant.decodeFromString<List<String>>(aliasesJson) }.getOrDefault(emptyList()),
    content = content,
    sources = decodeSources(),
    version = version,
    state = runCatching { MemoryDocumentState.valueOf(state) }.getOrDefault(MemoryDocumentState.ACTIVE),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun MemoryDocument.toDescriptor() = MemoryDocumentDescriptor(
    path = path,
    name = name,
    description = description,
    aliases = aliases,
    version = version,
)

private fun MemoryDocumentSearchHit.toDescriptor() = MemoryDocumentDescriptor(
    path = path,
    name = name,
    description = description,
    aliases = runCatching { JsonInstant.decodeFromString<List<String>>(aliasesJson) }.getOrDefault(emptyList()),
    version = version,
)

private fun MemoryDocumentEntity.decodeSources(): List<MemoryDocumentSource> = runCatching {
    JsonInstant.decodeFromString<List<MemoryDocumentSource>>(sourcesJson)
}.getOrDefault(emptyList())
