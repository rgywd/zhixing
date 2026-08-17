package me.rerere.rikkahub.data.db.fts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.createAppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryDocumentEntity
import me.rerere.rikkahub.data.repository.MemoryDocumentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDocumentFtsManagerInstrumentedTest {
    @Test
    fun chineseAliasAndMetadataWeightedEnglishSearchReturnDescriptors() = withDatabase { database ->
        runBlocking {
            val dao = database.memoryDocumentDao()
            dao.insertIgnore(
                document(
                    scopeId = "assistant",
                    path = "/areas/zhixing.md",
                    name = "Zhixing",
                    description = "Local Android assistant project.",
                    aliasesJson = "[\"知行\"]",
                    content = "- [stated] Offline recall is supported.",
                )
            )
            dao.insertIgnore(
                document(
                    scopeId = "assistant",
                    path = "/topics/phoenix.md",
                    name = "Phoenix",
                    description = "Named project routing metadata.",
                    content = "- [stated] Generic delivery context.",
                )
            )
            dao.insertIgnore(
                document(
                    scopeId = "assistant",
                    path = "/topics/content-only.md",
                    name = "Delivery notes",
                    description = "General delivery notes.",
                    content = "- [stated] Phoenix is mentioned in the body.",
                )
            )

            val manager = MemoryDocumentFtsManager(database)
            assertEquals(
                "/areas/zhixing.md",
                manager.search("知行", "/", 10, visibility("assistant")).single().path,
            )
            val phoenix = manager.search("Phoenix", "/topics", 10, visibility("assistant"))
            assertEquals("/topics/phoenix.md", phoenix.first().path)
            assertTrue(phoenix.all { it.path.startsWith("/topics/") })
        }
    }

    @Test
    fun triggersTrackUpdatesTombstonesReactivationAndScopeDeletionWithoutLeakingGlobalProjects() =
        withDatabase { database ->
            runBlocking {
                val dao = database.memoryDocumentDao()
                dao.insertIgnore(
                    document(
                        scopeId = MemoryDocumentRepository.GLOBAL_SCOPE_ID,
                        path = "/areas/global-private.md",
                        name = "Global private",
                        description = "Global project that assistants must not inherit.",
                        content = "- [stated] sharedtoken global project.",
                    )
                )
                dao.insertIgnore(
                    document(
                        scopeId = MemoryDocumentRepository.GLOBAL_SCOPE_ID,
                        path = MemoryDocumentRepository.PROFILE_PATH,
                        name = "Profile",
                        description = "Pinned global profile.",
                        content = "- [stated] sharedtoken pinned profile.",
                    )
                )
                dao.insertIgnore(
                    document(
                        scopeId = "assistant",
                        path = "/areas/local.md",
                        name = "Local",
                        description = "Assistant-local project.",
                        content = "- [stated] sharedtoken beforetoken.",
                    )
                )

                val manager = MemoryDocumentFtsManager(database)
                val visiblePaths = manager.search("sharedtoken", "/", 10, visibility("assistant")).map { it.path }
                assertEquals(
                    setOf(MemoryDocumentRepository.PROFILE_PATH, "/areas/local.md"),
                    visiblePaths.toSet(),
                )
                assertFalse("/areas/global-private.md" in visiblePaths)

                assertEquals(
                    1,
                    dao.compareAndSet(
                        scopeId = "assistant",
                        path = "/areas/local.md",
                        expectedVersion = 1,
                        name = "Local",
                        description = "Assistant-local project.",
                        aliasesJson = "[]",
                        content = "- [stated] aftertoken.",
                        sourcesJson = "[]",
                        updatedAt = 2,
                    )
                )
                assertTrue(manager.search("beforetoken", "/", 10, visibility("assistant")).isEmpty())
                assertEquals(
                    listOf("/areas/local.md"),
                    manager.search("aftertoken", "/", 10, visibility("assistant")).map { it.path },
                )

                assertEquals(1, dao.compareAndDelete("assistant", "/areas/local.md", 2, 3))
                assertTrue(manager.search("aftertoken", "/", 10, visibility("assistant")).isEmpty())
                assertEquals(
                    1,
                    dao.reactivateDeleted(
                        scopeId = "assistant",
                        path = "/areas/local.md",
                        name = "Local",
                        description = "Assistant-local project.",
                        aliasesJson = "[]",
                        content = "- [stated] aftertoken.",
                        sourcesJson = "[]",
                        updatedAt = 4,
                    )
                )
                assertEquals(
                    listOf("/areas/local.md"),
                    manager.search("aftertoken", "/", 10, visibility("assistant")).map { it.path },
                )

                dao.deleteScope("assistant")
                assertTrue(manager.search("aftertoken", "/", 10, visibility("assistant")).isEmpty())
            }
        }

    @Test
    fun reopeningDatabaseRebuildsTheDisposableProjection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.cacheDir.resolve("memory-fts-rebuild-${System.nanoTime()}").apply { mkdirs() }
        val databaseFile = directory.resolve("zhixing.db")
        try {
            var database = createAppDatabase(context, databaseFile.absolutePath)
            runBlocking {
                database.memoryDocumentDao().insertIgnore(
                    document(
                        scopeId = "assistant",
                        path = "/topics/rebuild.md",
                        name = "Rebuild",
                        description = "Projection rebuild coverage.",
                        content = "- [stated] rebuildtoken.",
                    )
                )
                database.openHelper.writableDatabase.execSQL("DELETE FROM memory_document_fts")
                assertTrue(
                    MemoryDocumentFtsManager(database)
                        .search("rebuildtoken", "/", 10, visibility("assistant"))
                        .isEmpty()
                )
            }
            database.close()

            database = createAppDatabase(context, databaseFile.absolutePath)
            try {
                runBlocking {
                    assertEquals(
                        listOf("/topics/rebuild.md"),
                        MemoryDocumentFtsManager(database)
                            .search("rebuildtoken", "/", 10, visibility("assistant"))
                            .map { it.path },
                    )
                }
            } finally {
                database.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun withDatabase(block: (AppDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.cacheDir.resolve("memory-fts-${System.nanoTime()}").apply { mkdirs() }
        val database = createAppDatabase(context, directory.resolve("zhixing.db").absolutePath)
        try {
            block(database)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun visibility(contextScopeId: String) = MemoryDocumentSearchVisibility(
        contextScopeId = contextScopeId,
        globalScopeId = MemoryDocumentRepository.GLOBAL_SCOPE_ID,
        globalPinnedPaths = MemoryDocumentRepository.PINNED_PATHS.sorted(),
    )

    private fun document(
        scopeId: String,
        path: String,
        name: String,
        description: String,
        aliasesJson: String = "[]",
        content: String,
    ) = MemoryDocumentEntity(
        scopeId = scopeId,
        path = path,
        name = name,
        description = description,
        aliasesJson = aliasesJson,
        content = content,
        updatedAt = 1,
    )
}
