package me.rerere.rikkahub.data.task

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantTaskRepositoryInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: AssistantTaskRepository
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AssistantTaskRepository(database, database.assistantTaskDao()) { now++ }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun eventsAreSequencedIdempotentAndTerminalStateCannotRegress() {
        runBlocking {
            val task = repository.create(
                title = "规划苏州行程",
                conversationId = "conversation-1",
                anchorMessageId = "message-1",
                anchorNodeId = "node-1",
            )
            repository.recordProgress(task.id, "正在查找交通", idempotencyKey = "step-1")
            repository.recordProgress(task.id, "重复通知", idempotencyKey = "step-1")
            repository.waitForInput(task.id, "需要你确认出发时间")
            repository.resume(task.id)
            repository.complete(task.id, "行程已整理完成")

            val stored = database.assistantTaskDao().getTask(task.id)!!
            val events = repository.observeEvents(task.id).first()
            assertEquals(AssistantTaskStatus.COMPLETED.name, stored.status)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), events.map { it.seq })
            assertEquals(1, events.count { it.idempotencyKey == "step-1" })
            var rejected = false
            try {
                repository.stop(task.id)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
        }
    }

    @Test
    fun startupReconciliationPreservesTaskAndCreatesRetryableEvent() = runBlocking {
        val task = repository.create(
            title = "整理资料",
            conversationId = "conversation-2",
            anchorMessageId = null,
            anchorNodeId = null,
        )

        assertEquals(1, repository.reconcileOnStartup())
        val interrupted = database.assistantTaskDao().getTask(task.id)!!
        assertEquals(AssistantTaskStatus.FAILED_RETRYABLE.name, interrupted.status)
        assertTrue(repository.observeEvents(task.id).first().any { it.errorCode == "APP_RESTARTED" })

        assertNull(
            repository.retryForSource(
                conversationId = "conversation-2",
                anchorMessageId = "another-message",
                anchorNodeId = "another-node",
            )
        )
        assertEquals(
            task.id,
            repository.retryForSource(
                conversationId = "conversation-2",
                anchorMessageId = null,
                anchorNodeId = null,
            )
        )
        val retried = database.assistantTaskDao().getTask(task.id)!!
        assertEquals(AssistantTaskStatus.RUNNING.name, retried.status)
        assertEquals(2, retried.attempt)
    }

    @Test
    fun newTaskReplacesFailedTaskAndDismissRemovesItsHistory() = runBlocking {
        val failed = repository.create(
            title = "旧写入",
            conversationId = "conversation-3",
            anchorMessageId = "message-old",
            anchorNodeId = "node-old",
        )
        repository.fail(failed.id, "WRITE_FAILED", "写入失败")

        val replacement = repository.create(
            title = "新写入",
            conversationId = "conversation-3",
            anchorMessageId = "message-new",
            anchorNodeId = "node-new",
        )

        assertNull(database.assistantTaskDao().getTask(failed.id))
        assertTrue(repository.observeEvents(failed.id).first().isEmpty())
        assertEquals(replacement.id, database.assistantTaskDao().getTask(replacement.id)?.id)

        repository.fail(replacement.id, "WRITE_FAILED", "再次失败")
        repository.dismissFailure(replacement.id)

        assertNull(database.assistantTaskDao().getTask(replacement.id))
        assertTrue(repository.observeEvents(replacement.id).first().isEmpty())
    }

    @Test
    fun expiredRetryableTasksArePrunedWithoutTouchingFreshFailures() = runBlocking {
        val expired = repository.create(
            title = "过期失败",
            conversationId = "conversation-4",
            anchorMessageId = "message-expired",
            anchorNodeId = "node-expired",
        )
        repository.fail(expired.id, "WRITE_FAILED", "写入失败")
        now += ASSISTANT_TASK_RETRY_RETENTION_MILLIS + 1
        assertNull(
            repository.retryForSource(
                conversationId = "conversation-4",
                anchorMessageId = "message-expired",
                anchorNodeId = "node-expired",
            )
        )
        val fresh = repository.create(
            title = "新失败",
            conversationId = "conversation-5",
            anchorMessageId = "message-fresh",
            anchorNodeId = "node-fresh",
        )
        repository.fail(fresh.id, "WRITE_FAILED", "写入失败")

        assertEquals(1, repository.pruneExpiredRetryableTasks())
        assertNull(database.assistantTaskDao().getTask(expired.id))
        assertEquals(fresh.id, database.assistantTaskDao().getTask(fresh.id)?.id)
    }

    @Test
    fun retryRequiresEveryProvidedSourceAnchorToMatch() = runBlocking {
        val task = repository.create(
            title = "精确重试",
            conversationId = "conversation-7",
            anchorMessageId = "message-7",
            anchorNodeId = "node-7",
        )
        repository.fail(task.id, "WRITE_FAILED", "写入失败")

        assertNull(
            repository.retryForSource(
                conversationId = "conversation-7",
                anchorMessageId = "message-7",
                anchorNodeId = "another-node",
            )
        )
        assertNull(
            repository.retryForSource(
                conversationId = "conversation-7",
                anchorMessageId = "another-message",
                anchorNodeId = "node-7",
            )
        )
        assertEquals(
            task.id,
            repository.retryForSource(
                conversationId = "conversation-7",
                anchorMessageId = "message-7",
                anchorNodeId = "node-7",
            )
        )
    }

    @Test
    fun deletingConversationTaskRecordsRemovesTaskAndEvents() = runBlocking {
        val task = repository.create(
            title = "随聊天删除",
            conversationId = "conversation-6",
            anchorMessageId = "message-6",
            anchorNodeId = "node-6",
        )
        repository.fail(task.id, "WRITE_FAILED", "写入失败")

        assertEquals(1, database.assistantTaskDao().deleteTasksForConversation("conversation-6"))
        assertNull(database.assistantTaskDao().getTask(task.id))
        assertTrue(repository.observeEvents(task.id).first().isEmpty())
    }
}
