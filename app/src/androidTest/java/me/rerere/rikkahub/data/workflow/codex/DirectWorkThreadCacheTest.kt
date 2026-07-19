package me.rerere.rikkahub.data.workflow.codex

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectWorkThreadCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "direct-work-thread-cache-test"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun directThreadSurvivesDatabaseReopenForOfflineReading() = runBlocking {
        context.deleteDatabase(databaseName)
        val json = Json { ignoreUnknownKeys = true }
        val original = CodexThreadDetail(
            thread = CodexThread(
                machineId = "direct",
                threadId = "thread-offline",
                projectId = "repo-1",
                name = "离线恢复",
                preview = "先缓存，再断网",
                createdAt = 100L,
                updatedAt = 200L,
                recencyAt = 200L,
                archived = false,
                source = "appServer",
                parentThreadId = null,
                forkedFromId = null,
                isSubagent = false,
                isAutomation = false,
                runtimeState = CodexRuntimeState.IDLE,
                rawStatus = "idle",
                isPinned = false,
            ),
            turns = listOf(
                CodexTurn(
                    turnId = "turn-1",
                    status = "completed",
                    startedAt = 110L,
                    completedAt = 190L,
                    error = null,
                    items = listOf(
                        CodexItem(
                            itemId = "item-1",
                            type = "agentMessage",
                            rawType = "agentMessage",
                            role = "agent",
                            text = "重启后仍然可见",
                            status = "completed",
                            raw = buildJsonObject { put("type", "agentMessage") },
                        )
                    ),
                )
            ),
            approvals = listOf(
                CodexApproval("approval-1", "approval", "不应离线恢复为可操作审批", 150L)
            ),
            attachments = mapOf(
                "/tmp/spec.md" to CodexAttachment("/tmp/spec.md", "content://spec", "spec.md", "text/markdown")
            ),
        )

        val firstDatabase = openDatabase()
        try {
            CodexCatalogRepository(firstDatabase.codexCatalogDao(), json).cacheDirectThread(original)
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = openDatabase()
        val restored: CodexThreadDetail?
        try {
            restored = CodexCatalogRepository(reopenedDatabase.codexCatalogDao(), json)
                .loadDirectThread("direct", "thread-offline")
        } finally {
            reopenedDatabase.close()
        }

        assertNotNull(restored)
        assertEquals("离线恢复", restored?.thread?.name)
        assertEquals(CodexRuntimeState.IDLE, restored?.thread?.runtimeState)
        assertEquals("重启后仍然可见", restored?.turns?.single()?.items?.single()?.text)
        assertEquals("content://spec", restored?.attachments?.get("/tmp/spec.md")?.localUri)
        assertTrue(restored?.approvals?.isEmpty() == true)
    }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        databaseName,
    ).build()
}
