package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MemoryDocumentDAO
import me.rerere.rikkahub.data.db.entity.MemoryDocumentEntity
import me.rerere.rikkahub.data.model.MemoryDocumentSource
import me.rerere.rikkahub.data.model.MemoryDocumentSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDocumentRepositoryTest {
    @Test
    fun compareAndSetRejectsASecondWriterUsingStaleVersion() = runBlocking {
        val repository = MemoryDocumentRepository(FakeMemoryDocumentDAO())
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
        val repository = MemoryDocumentRepository(FakeMemoryDocumentDAO())
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
        val repository = MemoryDocumentRepository(FakeMemoryDocumentDAO())
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
        val repository = MemoryDocumentRepository(FakeMemoryDocumentDAO())
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
