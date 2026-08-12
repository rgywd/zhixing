package me.rerere.rikkahub.ui.pages.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class AssistantTaskSourceChatPresentationTest {
    @Test
    fun `source chat does not render its own assistant task projection`() {
        val source = chatListSource()

        assertFalse(source.contains("AssistantTaskRepository"))
        assertFalse(source.contains("conversationTask"))
        assertFalse(source.contains("AssistantTaskCard(task = task, onClick = {})"))
    }

    private fun chatListSource(): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val relativePath = "src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt"
        val source = sequenceOf(
            File(workingDirectory, relativePath),
            File(workingDirectory, "app/$relativePath"),
        ).firstOrNull(File::isFile)
        checkNotNull(source) { "Unable to locate ChatList.kt from $workingDirectory" }
        return source.readText()
    }
}
