package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
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
    fun `right drawer uses material motion thresholds without the old trigger gesture`() {
        val source = agendaDrawerSource()

        assertTrue(source.contains("ModalDrawerSheet("))
        assertTrue(source.contains(".agendaDrawerDragGesture("))
        assertTrue(source.contains("AGENDA_DRAWER_POSITIONAL_THRESHOLD = 0.5f"))
        assertTrue(source.contains("AGENDA_DRAWER_ANIMATION_DURATION_MS = 256"))
        assertTrue(source.contains("AGENDA_DRAWER_VELOCITY_THRESHOLD = 400.dp"))
        assertFalse(source.contains("agendaSwipeGesture("))
        assertFalse(source.contains("AnimatedVisibility("))
        assertFalse(source.contains("threshold = 64.dp"))
    }

    @Test
    fun `slow drag settles at the halfway position`() {
        assertFalse(shouldOpenAgendaDrawer(openFraction = 0.49f, velocityX = 0f, velocityThresholdPx = 400f))
        assertTrue(shouldOpenAgendaDrawer(openFraction = 0.5f, velocityX = 0f, velocityThresholdPx = 400f))
    }

    @Test
    fun `fast fling direction overrides the halfway position`() {
        assertTrue(shouldOpenAgendaDrawer(openFraction = 0.1f, velocityX = -401f, velocityThresholdPx = 400f))
        assertFalse(shouldOpenAgendaDrawer(openFraction = 0.9f, velocityX = 401f, velocityThresholdPx = 400f))
    }

    @Test
    fun `closed right drawer claims only leftward horizontal drags`() {
        assertEquals(
            AgendaDrawerDragDecision.START,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = false,
                totalX = -20f,
                totalY = 2f,
                touchSlop = 8f,
            ),
        )
        assertEquals(
            AgendaDrawerDragDecision.IGNORE,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = false,
                totalX = 20f,
                totalY = 2f,
                touchSlop = 8f,
            ),
        )
        assertEquals(
            AgendaDrawerDragDecision.IGNORE,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = false,
                totalX = -10f,
                totalY = 20f,
                touchSlop = 8f,
            ),
        )
        assertEquals(
            AgendaDrawerDragDecision.WAIT,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = false,
                totalX = -6f,
                totalY = 1f,
                touchSlop = 8f,
            ),
        )
    }

    @Test
    fun `visible right drawer keeps ownership of horizontal reversal`() {
        assertEquals(
            AgendaDrawerDragDecision.START,
            agendaDrawerDragDecision(
                drawerVisible = true,
                gestureBlocked = true,
                totalX = 20f,
                totalY = 2f,
                touchSlop = 8f,
            ),
        )
        assertEquals(
            AgendaDrawerDragDecision.START,
            agendaDrawerDragDecision(
                drawerVisible = true,
                gestureBlocked = true,
                totalX = -20f,
                totalY = 2f,
                touchSlop = 8f,
            ),
        )
    }

    @Test
    fun `closed right drawer yields horizontal drags blocked by table or left drawer`() {
        assertEquals(
            AgendaDrawerDragDecision.IGNORE,
            agendaDrawerDragDecision(
                drawerVisible = false,
                gestureBlocked = true,
                totalX = -20f,
                totalY = 2f,
                touchSlop = 8f,
            ),
        )
    }

    @Test
    fun `visible right drawer keeps ownership even when content gesture is blocked`() {
        assertEquals(
            AgendaDrawerDragDecision.START,
            agendaDrawerDragDecision(
                drawerVisible = true,
                gestureBlocked = true,
                totalX = 20f,
                totalY = 2f,
                touchSlop = 8f,
            ),
        )
    }

    @Test
    fun `life overview greeting follows the time of day`() {
        assertTrue(lifeOverviewGreeting(7) == "早上好")
        assertTrue(lifeOverviewGreeting(12) == "中午好")
        assertTrue(lifeOverviewGreeting(16) == "下午好")
        assertTrue(lifeOverviewGreeting(21) == "晚上好")
        assertTrue(lifeOverviewGreeting(2) == "夜深了")
    }

    private fun agendaDrawerSource(): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val relativePath = "src/main/java/me/rerere/rikkahub/ui/pages/chat/AgendaDrawer.kt"
        val source = sequenceOf(
            File(workingDirectory, relativePath),
            File(workingDirectory, "app/$relativePath"),
        ).firstOrNull(File::isFile)
        checkNotNull(source) { "Unable to locate AgendaDrawer.kt from $workingDirectory" }
        return source.readText()
    }
}
