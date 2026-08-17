package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MemoryDocumentDAO
import me.rerere.rikkahub.data.db.entity.MemoryDocumentEntity
import me.rerere.rikkahub.data.db.fts.MemoryDocumentSearchHit
import me.rerere.rikkahub.data.db.fts.MemoryDocumentSearchIndex
import me.rerere.rikkahub.data.db.fts.MemoryDocumentSearchVisibility
import me.rerere.rikkahub.data.memory.MemoryDocumentFormatException
import me.rerere.rikkahub.data.model.MemoryDocumentSource
import me.rerere.rikkahub.data.model.MemoryDocumentSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDocumentRepositoryTest {
    @Test
    fun compareAndSetRejectsASecondWriterUsingStaleVersion() = runBlocking {
        val repository = repository()
        val source = MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = "conversation",
            messageId = "message",
            quote = "请记住知行项目",
        )
        val created = repository.writeFromChat(
            contextScopeId = MemoryDocumentRepository.GLOBAL_SCOPE_ID,
            rawPath = "/areas/zhixing.md",
            expectedVersion = 0,
            name = "Zhixing",
            description = "User-stated facts about the Zhixing project.",
            aliases = listOf("知行"),
            content = "- [stated] 知行是当前项目。",
            sources = listOf(source),
        )
        assertEquals(1L, created.version)

        val updated = repository.appendFromChat(
            contextScopeId = MemoryDocumentRepository.GLOBAL_SCOPE_ID,
            rawPath = created.path,
            expectedVersion = created.version,
            content = "- [stated] 知行是 Android-first。",
            sources = listOf(source.copy(messageId = "message-2")),
        )
        assertEquals(2L, updated.version)

        val conflict = runCatching {
            repository.appendFromChat(
                contextScopeId = MemoryDocumentRepository.GLOBAL_SCOPE_ID,
                rawPath = created.path,
                expectedVersion = created.version,
                content = "- [stated] 这次写入使用了旧版本。",
                sources = listOf(source.copy(messageId = "message-3")),
            )
        }.exceptionOrNull()
        assertTrue(conflict is MemoryDocumentConflictException)
        assertEquals(2L, (conflict as MemoryDocumentConflictException).current?.version)
    }

    @Test
    fun explicitlyDeletedPathCanBeCreatedAgainWithoutOverwritingAnActiveWriter() = runBlocking {
        val repository = repository()
        val source = MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = "conversation",
            messageId = "message",
            quote = "请记住这个项目",
        )
        val created = repository.writeFromChat(
            contextScopeId = "assistant",
            rawPath = "/areas/project.md",
            expectedVersion = 0,
            name = "Project",
            description = "Current project facts.",
            aliases = emptyList(),
            content = "- [stated] 这是当前项目。",
            sources = listOf(source),
        )
        repository.delete("assistant", created.path, created.version)

        val recreated = repository.writeFromChat(
            contextScopeId = "assistant",
            rawPath = created.path,
            expectedVersion = 0,
            name = "Project",
            description = "Recreated project facts.",
            aliases = emptyList(),
            content = "- [stated] 项目已重新开始。",
            sources = listOf(source.copy(messageId = "message-2")),
        )

        assertEquals(3L, recreated.version)
        assertEquals("- [stated] 项目已重新开始。", recreated.content)
    }

    @Test
    fun assistantScopeDoesNotInheritGlobalProjectDocuments() = runBlocking {
        val repository = repository()
        val source = MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = "conversation",
            messageId = "message",
            quote = "请记住全局项目",
        )
        repository.writeFromChat(
            contextScopeId = MemoryDocumentRepository.GLOBAL_SCOPE_ID,
            rawPath = "/areas/global-project.md",
            expectedVersion = 0,
            name = "Global project",
            description = "Global project facts.",
            aliases = emptyList(),
            content = "- [stated] 这是全局项目。",
            sources = listOf(source),
        )

        val localDocuments = repository.listDocuments("assistant")
        assertTrue(localDocuments.none { it.path == "/areas/global-project.md" })
        assertTrue(localDocuments.any { it.path == MemoryDocumentRepository.PROFILE_PATH })
        assertTrue(localDocuments.any { it.path == MemoryDocumentRepository.PREFERENCES_PATH })
    }

    @Test
    fun deletingRawHistoryRemovesOnlyItsProvenanceAndAdvancesVersion() = runBlocking {
        val repository = repository()
        val retained = MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = "conversation-2",
            messageId = "message-2",
            quote = "另一个来源",
        )
        val created = repository.writeFromChat(
            contextScopeId = "assistant",
            rawPath = "/topics/writing.md",
            expectedVersion = 0,
            name = "Writing",
            description = "User-stated writing facts.",
            aliases = emptyList(),
            content = "- [stated] 用户正在写作。",
            sources = listOf(
                retained,
                retained.copy(
                    conversationId = "conversation-1",
                    messageId = "message-1",
                    quote = "请记住我正在写作",
                ),
            ),
        )

        repository.revokeChatSources("conversation-1")

        val updated = repository.read("assistant", created.path)
        assertEquals(created.content, updated.content)
        assertEquals(created.version + 1, updated.version)
        assertEquals(listOf(retained), updated.sources)
    }

    @Test
    fun chatWritesRequireCanonicalFormatsWhileDirectUserEditsRemainAdvisory() = runBlocking {
        val repository = repository()
        val source = MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = "conversation",
            messageId = "message",
            quote = "我的生日是 10 月 17 日",
        )
        val content = "- [stated] 用户的生日是 10 月 17 日。"

        val rejected = runCatching {
            repository.writeFromChat(
                contextScopeId = MemoryDocumentRepository.GLOBAL_SCOPE_ID,
                rawPath = MemoryDocumentRepository.PROFILE_PATH,
                expectedVersion = 1,
                name = "Profile",
                description = "Stable profile",
                aliases = emptyList(),
                content = content,
                sources = listOf(source),
            )
        }.exceptionOrNull()
        assertTrue(rejected is MemoryDocumentFormatException)

        val edited = repository.writeFromUserEditor(
            contextScopeId = MemoryDocumentRepository.GLOBAL_SCOPE_ID,
            rawPath = MemoryDocumentRepository.PROFILE_PATH,
            expectedVersion = 1,
            name = "Profile",
            description = "Stable profile",
            aliases = emptyList(),
            content = content,
        )
        assertEquals(content, edited.content)
    }

    @Test
    fun writesNormalizeMetadataNewlinesAndKeepDistinctQuotesFromOneMessage() = runBlocking {
        val repository = repository()
        val source = MemoryDocumentSource(
            type = MemoryDocumentSourceType.CHAT,
            conversationId = "conversation",
            messageId = "message",
            quote = "第一条事实",
        )

        val created = repository.writeFromChat(
            contextScopeId = "assistant",
            rawPath = "/topics/format.md",
            expectedVersion = 0,
            name = "  Cafe\u0301  ",
            description = "  Stable formatting facts.  ",
            aliases = listOf("Cafe\u0301", "Café", "ZHIXING", "zhixing"),
            content = "- [stated] 第一条事实。  \r\n- [stated] 第二条事实。  \r\n",
            sources = listOf(source, source.copy(quote = "第二条事实")),
        )

        assertEquals("Café", created.name)
        assertEquals("Stable formatting facts.", created.description)
        assertEquals(listOf("Café", "ZHIXING"), created.aliases)
        assertEquals("- [stated] 第一条事实。\n- [stated] 第二条事实。", created.content)
        assertEquals(listOf("第一条事实", "第二条事实"), created.sources.map { it.quote })
    }

    @Test
    fun promptDocumentsContainOnlyPinnedFiles() = runBlocking {
        val repository = repository()
        repository.writeFromChat(
            contextScopeId = "assistant",
            rawPath = "/areas/private-project.md",
            expectedVersion = 0,
            name = "Private project",
            description = "Assistant-scoped durable project context.",
            aliases = listOf("Secret alias"),
            content = "- [stated] This body must be recalled on demand.",
            sources = listOf(source("private project")),
        )

        assertEquals(
            listOf(MemoryDocumentRepository.PREFERENCES_PATH, MemoryDocumentRepository.PROFILE_PATH),
            repository.getPromptDocuments("assistant").map { it.path }.sorted(),
        )
    }

    @Test
    fun listDescriptorsPagesEveryMatchingPathWithoutSilentOmission() = runBlocking {
        val repository = repository()
        repeat(23) { index ->
            val slug = "topic-${index.toString().padStart(2, '0')}"
            repository.writeFromChat(
                contextScopeId = "assistant",
                rawPath = "/topics/$slug.md",
                expectedVersion = 0,
                name = slug,
                description = "Durable facts for $slug.",
                aliases = emptyList(),
                content = "- [stated] $slug is active.",
                sources = listOf(source(slug)),
            )
        }

        val paths = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = repository.listDocumentDescriptors(
                contextScopeId = "assistant",
                rawPrefix = "/topics",
                rawCursor = cursor,
                limit = 7,
            )
            assertEquals(23, page.total)
            paths += page.items.map { it.path }
            cursor = page.nextCursor
        } while (page.hasMore)

        assertEquals(23, paths.size)
        assertEquals(paths.sorted(), paths)
        assertEquals(23, paths.distinct().size)
        assertTrue(paths.all { it.startsWith("/topics/") })
    }

    @Test
    fun findPreservesIndexOrderAndDefensivelyFiltersInvisibleScopes() = runBlocking {
        val index = FakeMemoryDocumentSearchIndex(
            listOf(
                hit("__global__", "/areas/global-secret.md", "Global secret"),
                hit("assistant", "/areas/phoenix.md", "Phoenix", aliasesJson = "[\"火鸟\"]"),
                hit("__global__", "/profile.md", "Profile"),
            )
        )
        val repository = repository(index)

        val result = repository.findDocuments(
            contextScopeId = "assistant",
            rawQuery = "phoenix",
            rawPrefix = "/areas",
            limit = 10,
        )

        assertEquals(listOf("/areas/phoenix.md"), result.items.map { it.path })
        assertEquals(listOf("火鸟"), result.items.single().aliases)
        assertFalse(result.truncated)
        assertEquals("phoenix", index.lastQuery)
        assertEquals("/areas", index.lastPrefix)
        assertEquals(11, index.lastLimit)
        assertEquals("assistant", index.lastVisibility?.contextScopeId)
        assertEquals(
            MemoryDocumentRepository.PINNED_PATHS.sorted(),
            index.lastVisibility?.globalPinnedPaths,
        )
    }

    private fun repository(
        searchIndex: MemoryDocumentSearchIndex = FakeMemoryDocumentSearchIndex(),
    ) = MemoryDocumentRepository(FakeMemoryDocumentDAO(), searchIndex)

    private fun source(quote: String) = MemoryDocumentSource(
        type = MemoryDocumentSourceType.CHAT,
        conversationId = "conversation",
        messageId = "message-$quote",
        quote = quote,
    )

    private fun hit(
        scopeId: String,
        path: String,
        name: String,
        aliasesJson: String = "[]",
    ) = MemoryDocumentSearchHit(
        scopeId = scopeId,
        path = path,
        name = name,
        description = "Routing metadata for $name.",
        aliasesJson = aliasesJson,
        version = 1,
    )
}

private class FakeMemoryDocumentSearchIndex(
    private val hits: List<MemoryDocumentSearchHit> = emptyList(),
) : MemoryDocumentSearchIndex {
    var lastQuery: String? = null
    var lastPrefix: String? = null
    var lastLimit: Int? = null
    var lastVisibility: MemoryDocumentSearchVisibility? = null

    override suspend fun search(
        query: String,
        prefix: String,
        limit: Int,
        visibility: MemoryDocumentSearchVisibility,
    ): List<MemoryDocumentSearchHit> {
        lastQuery = query
        lastPrefix = prefix
        lastLimit = limit
        lastVisibility = visibility
        return hits.take(limit)
    }
}

private class FakeMemoryDocumentDAO : MemoryDocumentDAO {
    private val state = MutableStateFlow<List<MemoryDocumentEntity>>(emptyList())

    override fun observeActive(scopeIds: List<String>): Flow<List<MemoryDocumentEntity>> = state

    override suspend fun listActive(scopeIds: List<String>): List<MemoryDocumentEntity> = state.value.filter {
        it.scopeId in scopeIds && it.state == "ACTIVE"
    }

    override suspend fun listWithSources(): List<MemoryDocumentEntity> =
        state.value.filter { it.sourcesJson != "[]" }

    override suspend fun find(scopeId: String, path: String): MemoryDocumentEntity? =
        state.value.firstOrNull { it.scopeId == scopeId && it.path == path }

    override suspend fun insertIgnore(entity: MemoryDocumentEntity): Long {
        if (find(entity.scopeId, entity.path) != null) return -1
        state.value += entity
        return 1
    }

    override suspend fun compareAndSet(
        scopeId: String,
        path: String,
        expectedVersion: Long,
        name: String,
        description: String,
        aliasesJson: String,
        content: String,
        sourcesJson: String,
        updatedAt: Long,
    ): Int {
        val current = find(scopeId, path) ?: return 0
        if (current.version != expectedVersion || current.state != "ACTIVE") return 0
        state.value = state.value.map {
            if (it.scopeId == scopeId && it.path == path) {
                it.copy(
                    name = name,
                    description = description,
                    aliasesJson = aliasesJson,
                    content = content,
                    sourcesJson = sourcesJson,
                    version = it.version + 1,
                    updatedAt = updatedAt,
                )
            } else {
                it
            }
        }
        return 1
    }

    override suspend fun reactivateDeleted(
        scopeId: String,
        path: String,
        name: String,
        description: String,
        aliasesJson: String,
        content: String,
        sourcesJson: String,
        updatedAt: Long,
    ): Int {
        val current = find(scopeId, path) ?: return 0
        if (current.state != "DELETED") return 0
        state.value = state.value.map {
            if (it.scopeId == scopeId && it.path == path) {
                it.copy(
                    name = name,
                    description = description,
                    aliasesJson = aliasesJson,
                    content = content,
                    sourcesJson = sourcesJson,
                    state = "ACTIVE",
                    version = it.version + 1,
                    updatedAt = updatedAt,
                )
            } else {
                it
            }
        }
        return 1
    }

    override suspend fun compareAndDelete(
        scopeId: String,
        path: String,
        expectedVersion: Long,
        updatedAt: Long,
    ): Int {
        val current = find(scopeId, path) ?: return 0
        if (current.version != expectedVersion || current.state != "ACTIVE") return 0
        state.value = state.value.map {
            if (it.scopeId == scopeId && it.path == path) {
                it.copy(
                    name = "",
                    description = "",
                    aliasesJson = "[]",
                    content = "",
                    sourcesJson = "[]",
                    state = "DELETED",
                    version = it.version + 1,
                    updatedAt = updatedAt,
                )
            } else {
                it
            }
        }
        return 1
    }


    override suspend fun compareAndSetSources(
        scopeId: String,
        path: String,
        expectedVersion: Long,
        sourcesJson: String,
        updatedAt: Long,
    ): Int {
        val current = find(scopeId, path) ?: return 0
        if (current.version != expectedVersion) return 0
        state.value = state.value.map {
            if (it.scopeId == scopeId && it.path == path) {
                it.copy(
                    sourcesJson = sourcesJson,
                    version = it.version + 1,
                    updatedAt = updatedAt,
                )
            } else {
                it
            }
        }
        return 1
    }

    override suspend fun deleteScope(scopeId: String) {
        state.value = state.value.filterNot { it.scopeId == scopeId }
    }
}
