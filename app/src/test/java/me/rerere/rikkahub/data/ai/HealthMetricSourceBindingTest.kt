package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.ToolExecutionException
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthMetricSourceBindingTest {
    @Test
    fun `binds exact quote to the newest matching user message`() {
        val older = UIMessage.user("体重是 72.4 kg")
        val latest = UIMessage.user("今天体重 72.4 kg，体脂率 19.1%")

        val source = bindHealthMetricChatSource(
            sourceQuote = "体重 72.4 kg，体脂率 19.1%",
            conversationId = "conversation-1",
            messages = listOf(older, latest, UIMessage.assistant("收到")),
            recordedAtEpochMillis = 1_800_000_000_000L,
        )

        assertEquals("conversation-1", source.conversationId)
        assertEquals(latest.id.toString(), source.messageId)
        assertEquals(1_800_000_000_000L, source.recordedAtEpochMillis)
    }

    @Test
    fun `rejects an inferred quote that was not stated by the user`() {
        val error = runCatching {
            bindHealthMetricChatSource(
                sourceQuote = "看起来体脂率大约 18%",
                conversationId = "conversation-1",
                messages = listOf(UIMessage.user("这是我今天的照片")),
            )
        }.exceptionOrNull()

        assertEquals("HEALTH_SOURCE_INVALID", (error as ToolExecutionException).code)
    }
}
