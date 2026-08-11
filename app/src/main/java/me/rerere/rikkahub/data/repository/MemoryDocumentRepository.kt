package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDocumentDAO
import me.rerere.rikkahub.data.db.entity.MemoryDocumentEntity
import me.rerere.rikkahub.data.memory.normalizeMemoryPath
import me.rerere.rikkahub.data.memory.MEMORY_DOCUMENT_SOURCE_LIMIT
import me.rerere.rikkahub.data.memory.requireMemorySources
import me.rerere.rikkahub.data.memory.requireValidMemoryDocument
import me.rerere.rikkahub.data.memory.requireWritableMemoryPath
import me.rerere.rikkahub.data.model.MemoryDocument
import me.rerere.rikkahub.data.model.MemoryDocumentSource
import me.rerere.rikkahub.data.model.MemoryDocumentSourceType
import me.rerere.rikkahub.data.model.MemoryDocumentState
import me.rerere.rikkahub.utils.JsonInstant

class MemoryDocumentConflictException(
    val current: MemoryDocument?,
) : IllegalStateException(
    current?.let { "Memory document version conflict; current version is ${it.version}" }
        ?: "Memory document version conflict; document no longer exists"
)

class MemoryDocumentRepository(
    private val dao: MemoryDocumentDAO,
) {
    companion object {
        const val GLOBAL_SCOPE_ID = "__global__"
        const val PROFILE_PATH = "/profile.md"
        const val PREFERENCES_PATH = "/preferences.md"
        val PINNED_PATHS = setOf(PROFILE_PATH, PREFERENCES_PATH)
    }

    fun observeDocuments(contextScopeId: String): Flow<List<MemoryDocument>> =
        dao.observeActive(scopeIds(contextScopeId)).map { preferScopedDocuments(it, contextScopeId) }

    suspend fun listDocuments(contextScopeId: String): List<MemoryDocument> {
        ensurePinnedDocuments()
        return preferScopedDocuments(dao.listActive(scopeIds(contextScopeId)), contextScopeId)
    }

    suspend fun getPromptDocuments(contextScopeId: String): List<MemoryDocument> = listDocuments(contextScopeId)

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
        return writeFromChat(
            contextScopeId = contextScopeId,
            rawPath = current.path,
            expectedVersion = expectedVersion,
            name = current.name,
            description = current.description,
            aliases = current.aliases,
            content = next,
            sources = current.sources + sources,
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
        return writeFromChat(
            contextScopeId = contextScopeId,
            rawPath = current.path,
            expectedVersion = expectedVersion,
            name = current.name,
            description = current.description,
            aliases = current.aliases,
            content = current.content.replaceRange(first, first + oldText.length, newText),
            sources = current.sources + sources,
        )
    }

    suspend fun delete(contextScopeId: String, rawPath: String, expectedVersion: Long) {
        val path = requireWritableMemoryPath(rawPath)
        require(path !in PINNED_PATHS) { "Pinned memory documents cannot be deleted; clear their content instead" }
        val current = findVisible(contextScopeId, path)?.toMemoryDocument()
            ?: throw MemoryDocumentConflictException(null)
        if (current.version != expectedVersion) throw MemoryDocumentConflictException(current)
        val changed = dao.compareAndDelete(
            scopeId = current.scopeId,
            path = path,
            expectedVersion = expectedVersion,
            updatedAt = System.currentTimeMillis(),
        )
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
        val cleanAliases = aliases.map(String::trim).distinct()
        val cleanContent = content.trim()
        requireValidMemoryDocument(path, name, description, cleanAliases, cleanContent)
        val cleanSources = sources
            .distinctBy { Triple(it.type, it.conversationId, it.messageId) }
            .takeLast(MEMORY_DOCUMENT_SOURCE_LIMIT)
        requireMemorySources(cleanSources, allowDirectUserEdit)
        ensurePinnedDocuments()
        val targetScope = if (path in PINNED_PATHS) GLOBAL_SCOPE_ID else contextScopeId
        val current = dao.find(targetScope, path)
        val now = System.currentTimeMillis()
        if (current == null) {
            if (expectedVersion != 0L) throw MemoryDocumentConflictException(null)
            val inserted = MemoryDocumentEntity(
                scopeId = targetScope,
                path = path,
                name = name.trim(),
                description = description.trim(),
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
                name = name.trim(),
                description = description.trim(),
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
        val changed = dao.compareAndSet(
            scopeId = targetScope,
            path = path,
            expectedVersion = expectedVersion,
            name = name.trim(),
            description = description.trim(),
            aliasesJson = JsonInstant.encodeToString(cleanAliases),
            content = cleanContent,
            sourcesJson = JsonInstant.encodeToString(cleanSources),
            updatedAt = now,
        )
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
}

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

private fun MemoryDocumentEntity.decodeSources(): List<MemoryDocumentSource> = runCatching {
    JsonInstant.decodeFromString<List<MemoryDocumentSource>>(sourcesJson)
}.getOrDefault(emptyList())
