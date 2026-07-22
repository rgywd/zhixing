package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaDrawerInteractionTest {
    @Test
    fun `top bar actions expand when at least 360dp is available`() {
        assertFalse(shouldExpandChatTopBarActions(359.dp))
        assertTrue(shouldExpandChatTopBarActions(360.dp))
        assertTrue(shouldExpandChatTopBarActions(411.dp))
    }

    @Test
    fun `left swipe opens agenda after crossing horizontal threshold`() {
        assertTrue(
            isAgendaSwipeTriggered(
                direction = AgendaSwipeDirection.OPEN,
                totalX = -64f,
                totalY = 12f,
                threshold = 64f,
            )
        )
    }

    @Test
    fun `right swipe closes agenda after crossing horizontal threshold`() {
        assertTrue(
            isAgendaSwipeTriggered(
                direction = AgendaSwipeDirection.CLOSE,
                totalX = 72f,
                totalY = 8f,
                threshold = 64f,
            )
        )
    }

    @Test
    fun `wrong direction short and vertical gestures do not trigger agenda`() {
        assertFalse(isAgendaSwipeTriggered(AgendaSwipeDirection.OPEN, 80f, 0f, 64f))
        assertFalse(isAgendaSwipeTriggered(AgendaSwipeDirection.CLOSE, -80f, 0f, 64f))
        assertFalse(isAgendaSwipeTriggered(AgendaSwipeDirection.OPEN, -48f, 0f, 64f))
        assertFalse(isAgendaSwipeTriggered(AgendaSwipeDirection.OPEN, -80f, 72f, 64f))
    }
}
