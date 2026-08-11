package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.AgendaTask
import me.rerere.rikkahub.data.model.AgendaTaskSource
import me.rerere.rikkahub.data.model.AgendaTaskStatus
import me.rerere.rikkahub.data.model.sourceConversationIdForNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgendaTaskEditorSourceTest {
    @Test
    fun `chat created task exposes its source conversation for editor navigation`() {
        assertEquals("conversation-1", task(AgendaTaskSource.CHAT, "conversation-1").sourceConversationIdForNavigation())
    }

    @Test
    fun `manual task never exposes a conversation navigation target`() {
        assertNull(task(AgendaTaskSource.MANUAL, "conversation-1").sourceConversationIdForNavigation())
    }

    private fun task(source: AgendaTaskSource, conversationId: String?) = AgendaTask(
        id = "task-1",
        title = "测试事项",
        note = "",
        status = AgendaTaskStatus.PENDING,
        dueAt = null,
        reminderAt = null,
        source = source,
        conversationId = conversationId,
        createdAt = 0L,
        updatedAt = 0L,
        completedAt = null,
    )
}
